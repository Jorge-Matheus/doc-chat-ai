package com.docchat.common.db;

/** Writes vectors in the text format of pgvector, as used with a cast to the vector type. */
public final class PgVectors {

    private PgVectors() {}

    public static String toLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }
}
