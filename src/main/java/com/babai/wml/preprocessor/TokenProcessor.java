package com.babai.wml.preprocessor;

import java.util.Set;

import com.babai.wml.tokenizer.Token;
import com.babai.wml.tokenizer.Tokenizer;

public interface TokenProcessor {

	void processToken(Tokenizer itor, Token t, StringBuilder body, Set<String> currentDefineArgs, boolean expand);
	
}
