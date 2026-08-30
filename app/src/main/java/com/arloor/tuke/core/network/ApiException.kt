package com.arloor.tuke.core.network

class ApiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)