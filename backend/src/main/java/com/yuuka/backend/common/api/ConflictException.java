package com.yuuka.backend.common.api;

public class ConflictException extends RuntimeException {
  public ConflictException(String message) {
    super(message);
  }
}
