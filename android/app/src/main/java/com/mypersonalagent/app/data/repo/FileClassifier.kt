package com.mypersonalagent.app.data.repo

object FileClassifier {
    private val pictures = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "tiff", "tif")
    private val documents = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "odt")
    private val code = setOf(
        "py", "js", "ts", "tsx", "jsx", "html", "css", "json", "java", "kt", "kts",
        "c", "cpp", "h", "cs", "go", "rs", "sh", "ps1", "sql", "rb", "php", "yaml", "yml", "xml",
    )

    fun category(fileName: String, mimeType: String? = null): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.orEmpty().lowercase()
        return when {
            ext in pictures || mime.startsWith("image/") -> "Pictures"
            ext in documents || mime in setOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain",
                "text/csv",
                "text/markdown",
            ) -> "Documents"
            ext in code -> "Code"
            mime.startsWith("video/") || mime.startsWith("audio/") -> "Others"
            else -> "Others"
        }
    }

    fun mimeOf(fileName: String, fallback: String?): String {
        if (!fallback.isNullOrBlank() && fallback != "application/octet-stream") return fallback
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            else -> fallback ?: "application/octet-stream"
        }
    }
}
