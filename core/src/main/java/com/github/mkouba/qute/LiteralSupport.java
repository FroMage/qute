package com.github.mkouba.qute;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.mkouba.qute.Results.Result;

class LiteralSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(LiteralSupport.class);

    static final Pattern INTEGER_LITERAL_PATTERN = Pattern.compile("(\\+|-)?\\d{1,10}");

    static final Pattern LONG_LITERAL_PATTERN = Pattern.compile("(\\+|-)?\\d{1,19}(L|l)");

    static Object getLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return Result.NOT_FOUND;
        }
        Object literal = Result.NOT_FOUND;
        if (Expression.isStringLiteralSeparator(value.charAt(0))) {
            literal = value.substring(1, value.length() - 1);
        } else if (value.equals("true")) {
            literal = Boolean.TRUE;
        } else if (value.equals("false")) {
            literal = Boolean.FALSE;
        } else if (value.equals("null")) {
            literal = null;
        } else if (INTEGER_LITERAL_PATTERN.matcher(value).matches()) {
            try {
                literal = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                LOGGER.warn("Unable to parse integer literal: " + value, e);
            }
        } else if (LONG_LITERAL_PATTERN.matcher(value).matches()) {
            try {
                literal = Long
                        .parseLong(value.substring(0, value.length() - 1));
            } catch (NumberFormatException e) {
                LOGGER.warn("Unable to parse long literal: " + value, e);
            }
        }
        return literal;
    }

}
