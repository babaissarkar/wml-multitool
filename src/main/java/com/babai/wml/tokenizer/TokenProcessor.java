package com.babai.wml.tokenizer;

import java.util.Set;

public interface TokenProcessor {

	void processToken(Tokenizer itor, Token t, StringBuilder body, Set<String> currentDefineArgs, boolean b);
	
}
