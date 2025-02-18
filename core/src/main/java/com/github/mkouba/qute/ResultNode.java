package com.github.mkouba.qute;

import java.util.function.Consumer;

/**
 * Node of a result tree.
 */
public interface ResultNode {
    
    static ResultNode NOOP = new ResultNode() {
        
        @Override
        public void process(Consumer<String> resultConsumer) {
        }
    };

    /**
     * 
     * @param resultConsumer
     */
    void process(Consumer<String> resultConsumer);
    
}
