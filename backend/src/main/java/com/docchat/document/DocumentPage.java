package com.docchat.document;

import java.util.UUID;

/** The text extracted from one page, which is the text passages and answers come from. */
record DocumentPage(UUID documentId, String filename, int pageNumber, int pageCount, String text) {}
