package io.invokt

/*
 * Platform-specific library file names used by the examples. Library naming
 * is (for now) deliberately the caller's job, so the tests own this too.
 */

/** Name of zlib, which ships with every macOS and Linux system. */
expect val zlibName: String

/** A library name that must not resolve on any machine. */
expect val missingLibName: String
