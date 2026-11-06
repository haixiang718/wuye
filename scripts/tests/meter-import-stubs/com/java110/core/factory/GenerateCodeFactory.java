package com.java110.core.factory;
/** Test-only identifiers; no Redis, sequence service, or business writes. */
public class GenerateCodeFactory {
    public static final String CODE_PREFIX_feeId="90";
    public static String getGeneratorId(String prefix) { return prefix+java.util.UUID.randomUUID().toString().replace("-","").substring(0,24); }
}
