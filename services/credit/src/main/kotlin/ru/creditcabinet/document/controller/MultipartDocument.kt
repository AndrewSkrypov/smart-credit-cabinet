package ru.creditcabinet.document.controller

import org.springframework.web.multipart.MultipartFile
import ru.creditcabinet.document.application.DocumentUpload

fun MultipartFile.toDocumentUpload(): DocumentUpload = DocumentUpload(originalFilename, size) { bytes }
