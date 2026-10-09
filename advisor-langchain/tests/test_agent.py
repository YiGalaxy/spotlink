import time
import asyncio
import json
import base64

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from fastapi import HTTPException
from langchain_core.messages import AIMessage

from app import RunRequest, ToolSpec, execute, verify, _seen, _active, cancel, CancelRequest


def request(**updates):
    values = dict(runId="test-run", conversationId="123", callbackToken="delegated", model="test-model",
                  system="仅按真实依据回答", message="查货后查规则", contextNote="铜20吨",
                  history=[{"role": "user", "content": "之前的问题"}],
                  tools=[ToolSpec(name="find", description="查货", parameters={"type": "object", "properties": {"keyword": {"type": "string"}}, "required": ["keyword"], "additionalProperties": False}),
                         ToolSpec(name="rules", description="查规则", parameters={"type": "object", "properties": {}, "additionalProperties": False})])
    return RunRequest(**(values | updates))


class Model:
    def __init__(self, replies):
        self.replies = iter(replies)
        self.prompts = []
        self.bound = []

    def bind_tools(self, tools):
        self.bound = tools
        return self

    async def ainvoke(self, prompt):
        self.prompts.append(prompt)
        return next(self.replies)


async def test_multistep_agent_documents_history_and_real_tool_messages():
    model = Model([
        AIMessage(content="", tool_calls=[{"name": "find", "args": {"keyword": "铜"}, "id": "one"}], usage_metadata={"input_tokens": 10, "output_tokens": 2, "total_tokens": 12}),
        AIMessage(content="", tool_calls=[{"name": "rules", "args": {}, "id": "two"}], usage_metadata={"input_tokens": 14, "output_tokens": 3, "total_tokens": 17}),
        AIMessage(content="查到铜，依据 APP-1 核对交收。", usage_metadata={"input_tokens": 18, "output_tokens": 5, "total_tokens": 23}),
    ])
    calls = []

    def handler(req):
        assert req.headers["Authorization"] == "Bearer delegated"
        body = json.loads(req.content)
        calls.append(body)
        refs = [] if body["name"] == "find" else [{"chunkId": "12", "docCode": "APP-1", "title": "交收", "version": "v1", "content": "需要确认交收。"}]
        return httpx.Response(200, json={"output": "真实数据", "knowledge": refs})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await execute(request(), client, lambda *a, **kw: model)
    assert result["iterations"] == 3
    assert result["inputTokens"] == 42
    assert result["outputTokens"] == 10
    assert [c["name"] for c in calls] == ["find", "rules"]
    assert any("之前的问题" in str(m.content) for m in model.prompts[0])
    assert any("APP-1" in str(m.content) and "需要确认交收" in str(m.content) for m in model.prompts[2])
    assert [m.tool_call_id for m in model.prompts[2] if m.type == "tool"] == ["one", "two"]


async def test_unknown_tool_and_injected_arguments_never_reach_backend():
    for name, arguments in [("execute_sql", {"sql": "DROP TABLE x"}), ("find", {"keyword": "铜", "enterpriseId": "other"})]:
        model = Model([AIMessage(content="", tool_calls=[{"name": name, "args": arguments, "id": "one"}])])
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: pytest.fail("Unexpected callback"))) as client:
            with pytest.raises(Exception):
                await execute(request(), client, lambda *a, **kw: model)


async def test_model_loop_and_tool_budgets_fail_closed():
    for replies in [
        [AIMessage(content="", tool_calls=[{"name": "rules", "args": {}, "id": str(i)}]) for i in range(6)],
        [AIMessage(content="", tool_calls=[{"name": "rules", "args": {}, "id": str(i)} for i in range(11)])],
    ]:
        model = Model(replies)
        calls = []
        def handler(req):
            calls.append(req)
            return httpx.Response(200, json={"output": "数据", "knowledge": []})
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with pytest.raises(ValueError):
                await execute(request(), client, lambda *a, **kw: model)
        assert len(calls) <= 10
        assert len(model.prompts) <= 6


async def test_unknown_usage_remains_null_and_memory_is_bounded():
    model = Model([AIMessage(content="最终回答")])
    async with httpx.AsyncClient() as client:
        result = await execute(request(history=[{"role": "user", "content": "长" * 20000}]), client, lambda *a, **kw: model)
    assert result["inputTokens"] is None
    assert sum(len(m.content) for m in model.prompts[0] if m.type == "human") < 11000


async def test_signed_delegation_audiences_expiry_scope_and_replay():
    _seen.clear()
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = base64.b64encode(private.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)).decode()
    now = int(time.time())
    claims = dict(iss="spotlink-advisor", sub="test-run", conversationId="123", userId="9", enterpriseId="18", purpose="advisor-run", iat=now, exp=now + 60)
    def token(audience, **changes):
        return jwt.encode(claims | {"aud": audience} | changes, private, algorithm="RS256")
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"key": public}))) as client:
        req = request(callbackToken=token("advisor-tools-model"))
        for credential in [None, "Bearer invalid", "Bearer " + token("spotlink-api"),
                           "Bearer " + token("langchain-engine", exp=now - 1),
                           "Bearer " + token("langchain-engine", enterpriseId="another")]:
            with pytest.raises(HTTPException):
                await verify(req, credential, client)
        await verify(req, "Bearer " + token("langchain-engine"), client)
        with pytest.raises(HTTPException):
            await verify(req, "Bearer " + token("langchain-engine"), client)


async def test_cancel_requires_signed_run_and_stops_running_task(monkeypatch):
    import app as module
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = base64.b64encode(private.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)).decode()
    client_factory = httpx.AsyncClient
    monkeypatch.setattr(module.httpx, "AsyncClient", lambda **kw: client_factory(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"key": public}))))
    now = int(time.time())
    token = jwt.encode(dict(iss="spotlink-advisor", aud="langchain-engine", sub="cancel-run", purpose="advisor-run",
                            conversationId="123", userId="9", enterpriseId="18", iat=now, exp=now + 60), private, algorithm="RS256")
    task = asyncio.create_task(asyncio.sleep(60))
    _active["cancel-run"] = task
    try:
        with pytest.raises(HTTPException):
            await cancel(CancelRequest(runId="cancel-run"), "Bearer invalid")
        assert not task.cancelled()
        await cancel(CancelRequest(runId="cancel-run"), "Bearer " + token)
        with pytest.raises(asyncio.CancelledError):
            await task
    finally:
        task.cancel()
        _active.pop("cancel-run", None)
