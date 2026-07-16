package io.invokt

/**
 * Validates a call's arguments against the declared signature, so every
 * backend fails with the same, readable [IllegalArgumentException] instead
 * of a platform-specific low-level error.
 */
internal fun requireMatchingArgs(functionName: String, params: List<CType<*>>, args: Array<out Any?>) {
    require(args.size == params.size) {
        "'$functionName' expects ${params.size} argument(s), got ${args.size}"
    }
    for (i in params.indices) {
        val param = params[i]
        val arg = args[i]
        val matches = when (param) {
            CType.Void -> false
            CType.I8 -> arg is Byte
            CType.I16 -> arg is Short
            CType.I32 -> arg is Int
            CType.I64 -> arg is Long
            CType.F32 -> arg is Float
            CType.F64 -> arg is Double
            CType.Pointer -> arg is Ptr
            CType.Bool -> arg is Boolean
            CType.U8 -> arg is UByte
            CType.U16 -> arg is UShort
            CType.U32 -> arg is UInt
            CType.U64 -> arg is ULong
            CType.Str -> arg is String
        }
        require(matches) {
            "'$functionName' argument $i: $param expects ${param.kotlinName}, " +
                "got ${arg?.let { it::class.simpleName } ?: "null"}"
        }
    }
}

private val CType<*>.kotlinName: String
    get() = when (this) {
        CType.Void -> "nothing (Void is not a parameter type)"
        CType.I8 -> "Byte"
        CType.I16 -> "Short"
        CType.I32 -> "Int"
        CType.I64 -> "Long"
        CType.F32 -> "Float"
        CType.F64 -> "Double"
        CType.Pointer -> "Ptr"
        CType.Bool -> "Boolean"
        CType.U8 -> "UByte"
        CType.U16 -> "UShort"
        CType.U32 -> "UInt"
        CType.U64 -> "ULong"
        CType.Str -> "String"
    }

/**
 * Shared validation for function/callback signatures.
 */
internal fun requireValidSignature(returns: CType<*>, params: List<CType<*>>, fixedArgs: Int, where: String) {
    params.forEachIndexed { i, p ->
        require(p != CType.Void) { "$where: parameter $i must not be Void" }
    }
    require(fixedArgs == -1 || fixedArgs in 0..params.size) {
        "$where: fixedArgs must be -1 (not variadic) or in 0..${params.size}, was $fixedArgs"
    }
}
