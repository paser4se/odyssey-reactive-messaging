package com.amadeus.middleware.odyssey.reactive.messaging.core.impl;

import java.lang.reflect.Method;
import java.util.Set;

import com.amadeus.middleware.odyssey.reactive.messaging.core.Message;
import com.amadeus.middleware.odyssey.reactive.messaging.core.MessageContext;

public interface MessageContextFactory {

  void add(Class<? extends MessageContext> returnType, Class<?> factoryClass, Method builder);

  Set<Class<? extends MessageContext>> getMessageContext();

  MessageContext create(Message message, Class<? extends MessageContext> type) throws FunctionInvocationException;
}
