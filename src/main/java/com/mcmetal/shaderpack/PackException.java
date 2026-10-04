package com.mcmetal.shaderpack;

/** A problem with a shaderpack's contents (missing file, bad syntax, unsupported feature). */
public class PackException extends RuntimeException {
	public PackException(final String message) {
		super(message);
	}

	public PackException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
