package com.spotlink.commodity.controller;

import com.spotlink.commodity.dto.CategoryNode;
import com.spotlink.commodity.service.CategoryService;
import com.spotlink.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "品类", description = "平台维护的商品品类树")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "品类树", description = "一次性返回完整树，前端直接渲染")
    @GetMapping("/tree")
    public ApiResponse<List<CategoryNode>> tree() {
        return ApiResponse.success(categoryService.tree());
    }
}
