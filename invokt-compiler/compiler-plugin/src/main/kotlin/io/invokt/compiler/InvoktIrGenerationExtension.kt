package io.invokt.compiler

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isByte
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isShort
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.platform.konan.isNative

/**
 * Replaces the bodies of `@Import`-annotated functions.
 *
 * For every import, a private top-level property caching the bound target is
 * added to the file, and the marker body is swapped for a call through it:
 *
 * Kotlin/Native — the cache holds a `CPointer<CFunction<...>>` resolved once
 * via `InvoktRuntime.resolve` (dlsym); the call site becomes a direct,
 * statically-typed C call. No libffi involved.
 *
 * JVM — the cache holds a `NativeFunction<*>` from `InvoktRuntime.bind`
 * (an FFM downcall handle); the call site invokes it.
 *
 * Pointer-typed values travel as raw Long addresses through the native call
 * (identical register class in every supported ABI) and are wrapped back
 * into `Ptr` at the boundary.
 */
internal class InvoktIrGenerationExtension(
    private val messages: MessageCollector,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val isNative = pluginContext.platform.isNative()
        if (!isNative && !pluginContext.platform.isJvm()) return
        val symbols = InvoktSymbols(pluginContext)
        for (file in moduleFragment.files) {
            // Snapshot: we append cache properties to the file while iterating.
            val imports = file.declarations
                .filterIsInstance<IrSimpleFunction>()
                .filter { it.hasAnnotation(IMPORT_FQ) }
            for (function in imports) {
                transform(file, function, symbols, isNative)
            }
        }
    }

    private fun transform(file: IrFile, function: IrSimpleFunction, symbols: InvoktSymbols, isNative: Boolean) {
        val regularParams = function.parameters.filter { it.kind == IrParameterKind.Regular }
        if (function.parameters.size != regularParams.size) {
            return error(function, "@Import functions must not have receivers")
        }
        val paramSigs = regularParams.map { param ->
            sigOf(param.type, isReturn = false)
                ?: return error(function, "@Import parameter '${param.name}' has unsupported type ${param.type.classFqName}")
        }
        val returnSig = sigOf(function.returnType, isReturn = true)
            ?: return error(function, "@Import return type ${function.returnType.classFqName} is not supported")

        val annotation = function.getAnnotation(IMPORT_FQ)!!
        val libraryName = annotation.constStringArgument(0)
        val symbolName = annotation.constStringArgument(1).ifEmpty { function.name.asString() }

        if (isNative) {
            generateNative(file, function, regularParams.size, paramSigs, returnSig, libraryName, symbolName, symbols)
        } else {
            generateJvm(file, function, regularParams.size, paramSigs, returnSig, libraryName, symbolName, symbols)
        }
    }

    // --- Kotlin/Native: cached CPointer<CFunction<...>> + direct call -------

    private fun generateNative(
        file: IrFile,
        function: IrSimpleFunction,
        arity: Int,
        paramSigs: List<Sig>,
        returnSig: Sig,
        libraryName: String,
        symbolName: String,
        symbols: InvoktSymbols,
    ) {
        val irBuiltIns = symbols.pluginContext.irBuiltIns
        // C-level signature: Ptr travels as Long, everything else as itself.
        val cParamTypes = paramSigs.map { it.lowered(symbols) }
        val cReturnType = returnSig.lowered(symbols)
        val cFunctionalType = irBuiltIns.functionN(arity).typeWith(*(cParamTypes + cReturnType).toTypedArray())
        val cFnType = symbols.cFunctionClass.typeWith(cFunctionalType)
        val pointerType = symbols.cPointerClass.typeWith(cFnType)

        // val fn$invokt: CPointer<CFunction<...>> =
        //     InvoktRuntime.resolve(lib, sym).toCPointer<CFunction<...>>() as CPointer<CFunction<...>>
        val resolveCall = IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.longType, symbols.resolve, 0).apply {
            arguments[0] = getRuntimeObject(symbols)
            arguments[1] = stringConst(irBuiltIns.stringType, libraryName)
            arguments[2] = stringConst(irBuiltIns.stringType, symbolName)
        }
        val toCPointerCall = IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pointerType.makeNullable(), symbols.toCPointer, 1).apply {
            typeArguments[0] = cFnType
            arguments[0] = resolveCall
        }
        val initializer = IrTypeOperatorCallImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            pointerType, IrTypeOperator.CAST, pointerType, toCPointerCall,
        )
        val cacheField = addCacheProperty(file, function, pointerType, initializer, symbols)

        // body: return fnPtr.invoke(args...) with Ptr/String <-> Long conversion.
        // String arguments are copied to temporary C strings that are freed
        // right after the call, hence the temporaries.
        val invokeSymbol = symbols.cinteropInvoke(arity)
        val builder = DeclarationIrBuilder(symbols.pluginContext, function.symbol)
        function.body = builder.irBlockBody {
            val regularParams = function.parameters.filter { it.kind == IrParameterKind.Regular }
            val cStringTemps = regularParams.mapIndexed { i, param ->
                if (paramSigs[i] != Sig.STR) return@mapIndexed null
                val alloc = IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.longType, symbols.allocCString, 0).apply {
                    arguments[0] = IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, param.type, param.symbol)
                }
                irTemporary(alloc, "cstr$i")
            }
            val call = IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, cReturnType, invokeSymbol, arity + 1).apply {
                (paramSigs + returnSig).forEachIndexed { i, sig -> typeArguments[i] = sig.lowered(symbols) }
                arguments[0] = irGetField(null, cacheField, pointerType)
                regularParams.forEachIndexed { i, param ->
                    val raw = IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, param.type, param.symbol)
                    arguments[i + 1] = when (paramSigs[i]) {
                        Sig.PTR -> IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.longType, symbols.ptrAddressGetter, 0)
                            .apply { arguments[0] = raw }
                        Sig.STR -> irGet(cStringTemps[i]!!)
                        else -> raw
                    }
                }
            }
            val rawResult: IrExpression = if (cStringTemps.any { it != null }) {
                val resultTemp = irTemporary(call, "result")
                cStringTemps.filterNotNull().forEach { temp ->
                    +IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.unitType, symbols.freeCString, 0)
                        .apply { arguments[0] = irGet(temp) }
                }
                irGet(resultTemp)
            } else {
                call
            }
            val result: IrExpression = when (returnSig) {
                Sig.PTR -> IrConstructorCallImpl(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    symbols.ptrClass.defaultType, symbols.ptrConstructor, 0, 0, null, SourceElement.NO_SOURCE,
                ).apply { arguments[0] = rawResult }
                Sig.STR -> IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.stringType, symbols.readCString, 0)
                    .apply { arguments[0] = rawResult }
                else -> rawResult
            }
            +irReturn(result)
        }
    }

    // --- JVM: cached NativeFunction<*> from InvoktRuntime.bind --------------

    private fun generateJvm(
        file: IrFile,
        function: IrSimpleFunction,
        arity: Int,
        paramSigs: List<Sig>,
        returnSig: Sig,
        libraryName: String,
        symbolName: String,
        symbols: InvoktSymbols,
    ) {
        val irBuiltIns = symbols.pluginContext.irBuiltIns
        val anyN = irBuiltIns.anyNType
        val handleType = symbols.nativeFunctionClass.typeWith(anyN)

        // val fn$invokt: NativeFunction<Any?> = InvoktRuntime.bind(lib, sym, CType.X, CType.Y, ...)
        val bindCall = IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, handleType, symbols.bind, 0).apply {
            arguments[0] = getRuntimeObject(symbols)
            arguments[1] = stringConst(irBuiltIns.stringType, libraryName)
            arguments[2] = stringConst(irBuiltIns.stringType, symbolName)
            arguments[3] = symbols.cTypeObject(returnSig)
            arguments[4] = IrVarargImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                irBuiltIns.arrayClass.typeWith(symbols.cTypeStarType),
                symbols.cTypeStarType,
                paramSigs.map { symbols.cTypeObject(it) },
            )
        }
        val cacheField = addCacheProperty(file, function, handleType, bindCall, symbols)

        // body: return handle.invoke(args...) as R
        val builder = DeclarationIrBuilder(symbols.pluginContext, function.symbol)
        val invokeCall = IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, anyN, symbols.nativeFunctionInvoke, 0).apply {
            arguments[0] = builder.irGetField(null, cacheField, handleType)
            arguments[1] = IrVarargImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                irBuiltIns.arrayClass.typeWith(anyN),
                anyN,
                function.parameters.filter { it.kind == IrParameterKind.Regular }
                    .map { IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, it.type, it.symbol) },
            )
        }
        function.body = builder.irBlockBody {
            if (returnSig == Sig.VOID) {
                +invokeCall
            } else {
                +irReturn(
                    IrTypeOperatorCallImpl(
                        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                        function.returnType, IrTypeOperator.CAST, function.returnType, invokeCall,
                    ),
                )
            }
        }
    }

    // --- shared helpers ------------------------------------------------------

    /** Adds `private val <fn>$invokt` with [initializer] to [file], returns its backing field. */
    private fun addCacheProperty(
        file: IrFile,
        function: IrSimpleFunction,
        type: IrType,
        initializer: IrExpression,
        symbols: InvoktSymbols,
    ): IrField {
        val factory = symbols.pluginContext.irFactory
        val name = Name.identifier("${function.name.asString()}\$invokt${symbols.nextId()}")
        val field = factory.buildField {
            this.name = name
            this.type = type
            isFinal = true
            isStatic = true
            visibility = DescriptorVisibilities.PRIVATE
            origin = IrDeclarationOrigin.GeneratedByPlugin(InvoktPluginKey)
        }.apply {
            parent = file
            this.initializer = factory.createExpressionBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, initializer)
        }
        val property = factory.buildProperty {
            this.name = name
            visibility = DescriptorVisibilities.PRIVATE
            origin = IrDeclarationOrigin.GeneratedByPlugin(InvoktPluginKey)
        }.apply {
            parent = file
            backingField = field
            field.correspondingPropertySymbol = symbol
        }
        property.getter = factory.buildFun {
            this.name = Name.special("<get-$name>")
            returnType = type
            visibility = DescriptorVisibilities.PRIVATE
            origin = IrDeclarationOrigin.GeneratedByPlugin(InvoktPluginKey)
        }.apply {
            parent = file
            correspondingPropertySymbol = property.symbol
            val builder = DeclarationIrBuilder(symbols.pluginContext, symbol)
            body = builder.irBlockBody { +irReturn(irGetField(null, field, type)) }
        }
        file.declarations += property
        return field
    }

    private fun getRuntimeObject(symbols: InvoktSymbols): IrExpression =
        IrGetObjectValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, symbols.runtimeObject.defaultType, symbols.runtimeObject)

    private fun sigOf(type: IrType, isReturn: Boolean): Sig? = when {
        type.isByte() -> Sig.I8
        type.isShort() -> Sig.I16
        type.isInt() -> Sig.I32
        type.isLong() -> Sig.I64
        type.isFloat() -> Sig.F32
        type.isDouble() -> Sig.F64
        type.isBoolean() -> Sig.BOOL
        type.isString() -> Sig.STR
        type.classFqName == PTR_FQ -> Sig.PTR
        type.classFqName == FqName("kotlin.UByte") -> Sig.U8
        type.classFqName == FqName("kotlin.UShort") -> Sig.U16
        type.classFqName == FqName("kotlin.UInt") -> Sig.U32
        type.classFqName == FqName("kotlin.ULong") -> Sig.U64
        type.isUnit() && isReturn -> Sig.VOID
        else -> null
    }

    private fun error(function: IrSimpleFunction, message: String) {
        messages.report(
            CompilerMessageSeverity.ERROR,
            "invokt: $message (function '${function.name}')",
        )
    }

    private fun stringConst(type: IrType, value: String): IrExpression =
        IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, value)

    private fun IrConstructorCall.constStringArgument(index: Int): String =
        (arguments.getOrNull(index) as? IrConst)?.value as? String ?: ""
}

internal enum class Sig { I8, I16, I32, I64, F32, F64, BOOL, U8, U16, U32, U64, PTR, STR, VOID }

/** The Kotlin type a value has inside the C-level CFunction signature. */
private fun Sig.lowered(symbols: InvoktSymbols): IrType {
    val b = symbols.pluginContext.irBuiltIns
    return when (this) {
        Sig.I8 -> b.byteType
        Sig.I16 -> b.shortType
        Sig.I32 -> b.intType
        Sig.I64, Sig.PTR, Sig.STR -> b.longType // pointers travel as raw addresses
        Sig.F32 -> b.floatType
        Sig.F64 -> b.doubleType
        Sig.BOOL -> b.booleanType
        Sig.U8 -> symbols.uByteType
        Sig.U16 -> symbols.uShortType
        Sig.U32 -> symbols.uIntType
        Sig.U64 -> symbols.uLongType
        Sig.VOID -> b.unitType
    }
}

private val INVOKT_PKG = FqName("io.invokt")
private val IMPORT_FQ = FqName("io.invokt.Import")
private val PTR_FQ = FqName("io.invokt.Ptr")
private val CINTEROP_PKG = FqName("kotlinx.cinterop")

internal object InvoktPluginKey : GeneratedDeclarationKey()

/** Lazily resolved references into the invokt runtime and kotlinx.cinterop. */
internal class InvoktSymbols(val pluginContext: IrPluginContext) {

    private var idCounter = 0
    fun nextId(): Int = idCounter++

    val runtimeObject: IrClassSymbol by lazy {
        pluginContext.referenceClass(ClassId(INVOKT_PKG, Name.identifier("InvoktRuntime")))
            ?: missing("io.invokt.InvoktRuntime")
    }
    val ptrClass: IrClassSymbol by lazy {
        pluginContext.referenceClass(ClassId(INVOKT_PKG, Name.identifier("Ptr"))) ?: missing("io.invokt.Ptr")
    }
    val ptrConstructor by lazy { ptrClass.constructors.single() }
    val ptrAddressGetter: IrSimpleFunctionSymbol by lazy {
        pluginContext.referenceProperties(CallableId(ClassId(INVOKT_PKG, Name.identifier("Ptr")), Name.identifier("address")))
            .single().owner.getter!!.symbol
    }
    val nativeFunctionClass: IrClassSymbol by lazy {
        pluginContext.referenceClass(ClassId(INVOKT_PKG, Name.identifier("NativeFunction")))
            ?: missing("io.invokt.NativeFunction")
    }
    val nativeFunctionInvoke: IrSimpleFunctionSymbol by lazy {
        pluginContext.referenceFunctions(
            CallableId(ClassId(INVOKT_PKG, Name.identifier("NativeFunction")), Name.identifier("invoke")),
        ).single()
    }
    val bind: IrSimpleFunctionSymbol by lazy { runtimeFunction("bind") }
    val resolve: IrSimpleFunctionSymbol by lazy { runtimeFunction("resolve") }

    val allocCString: IrSimpleFunctionSymbol by lazy { topLevelFunction("invoktAllocCString") }
    val freeCString: IrSimpleFunctionSymbol by lazy { topLevelFunction("invoktFreeCString") }
    val readCString: IrSimpleFunctionSymbol by lazy { topLevelFunction("invoktReadCString") }

    val uByteType: IrType by lazy { unsignedType("UByte") }
    val uShortType: IrType by lazy { unsignedType("UShort") }
    val uIntType: IrType by lazy { unsignedType("UInt") }
    val uLongType: IrType by lazy { unsignedType("ULong") }

    private fun unsignedType(name: String): IrType =
        (pluginContext.referenceClass(ClassId(FqName("kotlin"), Name.identifier(name))) ?: missing("kotlin.$name"))
            .defaultType

    private fun topLevelFunction(name: String): IrSimpleFunctionSymbol =
        pluginContext.referenceFunctions(CallableId(INVOKT_PKG, Name.identifier(name))).singleOrNull()
            ?: missing("io.invokt.$name")

    val cTypeStarType: IrType by lazy {
        val cType = pluginContext.referenceClass(ClassId(INVOKT_PKG, Name.identifier("CType"))) ?: missing("io.invokt.CType")
        cType.typeWith(pluginContext.irBuiltIns.anyNType)
    }

    fun cTypeObject(sig: Sig): IrExpression {
        val name = when (sig) {
            Sig.I8 -> "I8"; Sig.I16 -> "I16"; Sig.I32 -> "I32"; Sig.I64 -> "I64"
            Sig.F32 -> "F32"; Sig.F64 -> "F64"; Sig.PTR -> "Pointer"; Sig.VOID -> "Void"
            Sig.BOOL -> "Bool"; Sig.U8 -> "U8"; Sig.U16 -> "U16"; Sig.U32 -> "U32"
            Sig.U64 -> "U64"; Sig.STR -> "Str"
        }
        val classId = ClassId(INVOKT_PKG, Name.identifier("CType")).createNestedClassId(Name.identifier(name))
        val symbol = pluginContext.referenceClass(classId) ?: missing("io.invokt.CType.$name")
        return IrGetObjectValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, symbol.defaultType, symbol)
    }

    val cPointerClass: IrClassSymbol by lazy {
        pluginContext.referenceClass(ClassId(CINTEROP_PKG, Name.identifier("CPointer"))) ?: missing("kotlinx.cinterop.CPointer")
    }
    val cFunctionClass: IrClassSymbol by lazy {
        pluginContext.referenceClass(ClassId(CINTEROP_PKG, Name.identifier("CFunction"))) ?: missing("kotlinx.cinterop.CFunction")
    }
    val toCPointer: IrSimpleFunctionSymbol by lazy {
        pluginContext.referenceFunctions(CallableId(CINTEROP_PKG, Name.identifier("toCPointer")))
            .single { sym ->
                sym.owner.parameters.singleOrNull { it.kind == IrParameterKind.ExtensionReceiver }?.type?.isLong() == true
            }
    }

    /** `operator fun <P1..Pn, R> CPointer<CFunction<(P1..Pn) -> R>>.invoke(...)`. */
    fun cinteropInvoke(arity: Int): IrSimpleFunctionSymbol =
        pluginContext.referenceFunctions(CallableId(CINTEROP_PKG, Name.identifier("invoke")))
            .single { sym ->
                val fn = sym.owner
                fn.typeParameters.size == arity + 1 &&
                    fn.parameters.count { it.kind == IrParameterKind.Regular } == arity &&
                    fn.parameters.singleOrNull { it.kind == IrParameterKind.ExtensionReceiver }
                        ?.type?.classFqName == FqName("kotlinx.cinterop.CPointer")
            }

    private fun runtimeFunction(name: String): IrSimpleFunctionSymbol =
        pluginContext.referenceFunctions(
            CallableId(ClassId(INVOKT_PKG, Name.identifier("InvoktRuntime")), Name.identifier(name)),
        ).single()

    private fun missing(what: String): Nothing =
        error("invokt compiler plugin: '$what' not found — is the io.invokt runtime library a dependency of this compilation?")
}
