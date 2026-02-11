package com.fintrack.controller;

import com.fintrack.dto.CategoryRequest;
import com.fintrack.dto.CategoryResponse;
import com.fintrack.service.CurrentUserService;
import com.fintrack.service.TransactionCategoryService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finance/categories")
public class CategoryController {
  private final TransactionCategoryService categoryService;
  private final CurrentUserService currentUserService;

  public CategoryController(TransactionCategoryService categoryService, CurrentUserService currentUserService) {
    this.categoryService = categoryService;
    this.currentUserService = currentUserService;
  }

  @GetMapping
  public List<CategoryResponse> listCategories() {
    UUID userId = currentUserService.requireUserId();
    return categoryService.list(userId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CategoryResponse createCategory(@RequestBody CategoryRequest request) {
    UUID userId = currentUserService.requireUserId();
    return categoryService.create(userId, request);
  }

  @PatchMapping("/{categoryId}")
  public CategoryResponse updateCategory(@PathVariable UUID categoryId, @RequestBody CategoryRequest request) {
    UUID userId = currentUserService.requireUserId();
    return categoryService.update(userId, categoryId, request);
  }

  @DeleteMapping("/{categoryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteCategory(@PathVariable UUID categoryId) {
    UUID userId = currentUserService.requireUserId();
    categoryService.delete(userId, categoryId);
  }
}
