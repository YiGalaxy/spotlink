-- 旧帮助文档含未实现的清算、强制保证金与协议交易；保留历史但停止检索。
UPDATE t_knowledge_doc SET status=0, remark='旧示例帮助说明，已停用；当前操作以 APP-* 及新版 GUIDE-* 为准'
WHERE doc_code IN ('GUIDE-MARGIN','GUIDE-FEE','GUIDE-QUICKSTART');
