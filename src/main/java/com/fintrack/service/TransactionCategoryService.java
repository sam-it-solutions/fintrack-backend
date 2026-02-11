package com.fintrack.service;

import com.fintrack.dto.CategoryRequest;
import com.fintrack.dto.CategoryResponse;
import com.fintrack.model.TransactionCategory;
import com.fintrack.model.User;
import com.fintrack.repository.TransactionCategoryRepository;
import com.fintrack.repository.UserRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransactionCategoryService {
  private static final List<String> DEFAULT_CATEGORIES = Arrays.asList(
      "Boodschappen",
      "Horeca",
      "Transport",
      "Shopping",
      "Abonnementen",
      "Utilities",
      "Huur/Hypotheek",
      "Gezondheid",
      "Onderwijs",
      "Cash",
      "Transfer",
      "Inkomen",
      "Crypto",
      "Overig"
  );

  private final TransactionCategoryRepository categoryRepository;
  private final UserRepository userRepository;

  public TransactionCategoryService(TransactionCategoryRepository categoryRepository, UserRepository userRepository) {
    this.categoryRepository = categoryRepository;
    this.userRepository = userRepository;
  }

  public List<CategoryResponse> list(UUID userId) {
    List<TransactionCategory> categories = categoryRepository.findByUserIdOrderByNameAsc(userId);
    if (categories.isEmpty()) {
      seedDefaults(userId);
      categories = categoryRepository.findByUserIdOrderByNameAsc(userId);
    }
    return categories.stream().map(this::toResponse).toList();
  }

  public CategoryResponse create(UUID userId, CategoryRequest request) {
    String name = normalizeName(request.getName());
    if (name == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required");
    }
    categoryRepository.findByUserIdAndNameIgnoreCase(userId, name)
        .ifPresent(existing -> {
          throw new ResponseStatusException(HttpStatus.CONFLICT, "Category already exists");
        });
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    TransactionCategory category = new TransactionCategory();
    category.setUser(user);
    category.setName(name);
    TransactionCategory saved = categoryRepository.save(category);
    return toResponse(saved);
  }

  public CategoryResponse update(UUID userId, UUID categoryId, CategoryRequest request) {
    TransactionCategory category = requireCategory(userId, categoryId);
    String name = normalizeName(request.getName());
    if (name == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required");
    }
    categoryRepository.findByUserIdAndNameIgnoreCase(userId, name)
        .filter(existing -> !existing.getId().equals(categoryId))
        .ifPresent(existing -> {
          throw new ResponseStatusException(HttpStatus.CONFLICT, "Category already exists");
        });
    category.setName(name);
    TransactionCategory saved = categoryRepository.save(category);
    return toResponse(saved);
  }

  public void delete(UUID userId, UUID categoryId) {
    TransactionCategory category = requireCategory(userId, categoryId);
    categoryRepository.delete(category);
  }

  private void seedDefaults(UUID userId) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    for (String categoryName : DEFAULT_CATEGORIES) {
      categoryRepository.findByUserIdAndNameIgnoreCase(userId, categoryName)
          .orElseGet(() -> {
            TransactionCategory category = new TransactionCategory();
            category.setUser(user);
            category.setName(categoryName);
            return categoryRepository.save(category);
          });
    }
  }

  private TransactionCategory requireCategory(UUID userId, UUID categoryId) {
    TransactionCategory category = categoryRepository.findById(categoryId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
    if (!category.getUser().getId().equals(userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
    }
    return category;
  }

  private String normalizeName(String value) {
    if (value == null) {
      return null;
    }
    String cleaned = value.trim();
    return cleaned.isEmpty() ? null : cleaned;
  }

  private CategoryResponse toResponse(TransactionCategory category) {
    return new CategoryResponse(
        category.getId(),
        category.getName(),
        category.getCreatedAt(),
        category.getUpdatedAt()
    );
  }
}
