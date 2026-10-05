package com.sheltr.publisher.text

/** The extracted text of one page. Page numbers start at 1. */
data class PageText(val pageNumber: Int, val text: String) {
    init {
        require(pageNumber >= 1) { "pageNumber must be >= 1" }
    }
}
