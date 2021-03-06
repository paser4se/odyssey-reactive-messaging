package com.amadeus.middleware.odyssey.reactive.messaging.core.impl;

import static com.amadeus.middleware.odyssey.reactive.messaging.core.FunctionInvoker.Signature.DIRECT;
import static com.amadeus.middleware.odyssey.reactive.messaging.core.FunctionInvoker.Signature.PUBLISHER_PUBLISHER;
import static com.amadeus.middleware.odyssey.reactive.messaging.core.FunctionInvoker.Signature.UNKNOWN;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.reactive.streams.operators.PublisherBuilder;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amadeus.middleware.odyssey.reactive.messaging.core.Async;
import com.amadeus.middleware.odyssey.reactive.messaging.core.FunctionInvoker;
import com.amadeus.middleware.odyssey.reactive.messaging.core.Message;
import com.amadeus.middleware.odyssey.reactive.messaging.core.impl.cdi.MessageScopedContext;

public class ReflectiveFunctionInvoker implements FunctionInvoker {
  private static final Logger logger = LoggerFactory.getLogger(ReflectiveFunctionInvoker.class);

  private Class<?> targetClass;
  private Method targetMethod;
  private Object defaultTargetInstance;
  private boolean contextActivation;

  public ReflectiveFunctionInvoker(Class<?> targetClass, Method targetMethod) {
    this(targetClass, targetMethod, false);
  }

  public ReflectiveFunctionInvoker(Class<?> targetClass, Method targetMethod, boolean contextActivation) {
    this.targetClass = targetClass;
    this.targetMethod = targetMethod;
    this.contextActivation = contextActivation;
  }

  public ReflectiveFunctionInvoker(Object defaultTargetInstance, Class<?> targetClass, Method targetMethod,
      boolean contextActivation) {
    this(targetClass, targetMethod, contextActivation);
    this.defaultTargetInstance = defaultTargetInstance;
  }

  private static boolean isMessagePublisher(Type type) {
    if (!ParameterizedType.class.isAssignableFrom(type.getClass())) {
      return false;
    }
    ParameterizedType parameterizedType = (ParameterizedType) type;
    if (!(parameterizedType.getRawType() instanceof Class<?>)) {
      return false;
    }
    Class classType = (Class<?>) parameterizedType.getRawType();
    if (!Publisher.class.isAssignableFrom(classType)) {
      return false;
    }
    Type paramType = parameterizedType.getActualTypeArguments()[0];
    if (!ParameterizedType.class.isAssignableFrom(paramType.getClass())) {
      return false;
    }
    return Message.class.isAssignableFrom((Class<?>) ((ParameterizedType) paramType).getRawType());
  }

  public Class<?> getTargetClass() {
    return targetClass;
  }

  public Method getMethod() {
    return targetMethod;
  }

  @Override
  public Signature getSignature() {
    Class<?> returnType = targetMethod.getReturnType();
    if (void.class.isAssignableFrom(returnType)) {
      return DIRECT;
    }
    if (isMessagePublisher(targetMethod.getGenericReturnType())) {
      if (targetMethod.getParameterCount() != 1) {
        return UNKNOWN;
      }
      Parameter parameter = targetMethod.getParameters()[0];
      if (isMessagePublisher(parameter.getParameterizedType())) {
        return PUBLISHER_PUBLISHER;
      }
    }
    return UNKNOWN;
  }

  @Override
  public void initialize() {
  }

  // @Override
  public void setTargetInstance(Object targetInstance) {
    defaultTargetInstance = targetInstance;
  }

  // @Override
  public Object getTargetInstance() {
    return defaultTargetInstance;
  }

  @Override
  public Object invoke(Message<?> message) throws FunctionInvocationException {
    MessageScopedContext activatedContext = null;
    if (contextActivation) {
      MessageImpl<?> messageImpl = (MessageImpl<?>) message;
      MessageScopedContext context = MessageScopedContext.getInstance();
      if (!context.isActive()) {
        activatedContext = context;
        activatedContext.start(messageImpl.getScopeContextId());
      }
    }
    Object[] parameters = buildParameters(message);
    try {
      return targetMethod.invoke(defaultTargetInstance, parameters);
    } catch (Exception e) {
      throw new FunctionInvocationException(e);
    } finally {
      if (activatedContext != null) {
        activatedContext.suspend();
      }
    }
  }

  @Override
  public Object invoke(PublisherBuilder<Message<?>> publisher) throws FunctionInvocationException {
    try {
      return targetMethod.invoke(defaultTargetInstance, publisher.buildRs());
    } catch (Exception e) {
      throw new FunctionInvocationException(e);
    }
  }

  private Object[] buildParameters(Message<?> message) {
    List<Object> parameters = new ArrayList<>();
    for (Parameter param : targetMethod.getParameters()) {
      // Special handling of Async as it is a parameterized type
      if (Async.class.isAssignableFrom(param.getType())) {
        ParameterizedType type = (ParameterizedType) param.getParameterizedType();
        Type parameterType = type.getActualTypeArguments()[0];
        if (ParameterizedType.class.isAssignableFrom(parameterType.getClass())) {
          parameterType = ((ParameterizedType) parameterType).getRawType();
        }
        Class<?> clazz = (Class<?>) parameterType;
        parameters.add(new DirectAsync<>(MessageImpl.get(message, clazz)));
        continue;
      }

      // Message Scoped object
      Object object = MessageImpl.get(message, param.getType());
      if (object != null) {
        parameters.add(object);
        continue;
      }

      // Try payload resolution
      Object payload = message.getPayload();
      if ((payload != null) && (param.getType()
          .isAssignableFrom(payload.getClass()))) {
        parameters.add(payload);
        continue;
      }

      // Here we have nothing as a parameter, let's use null
      // TODO: Or should it send an exception and kill the stream?
      logger.warn("null parameter injections for {}.{} {} {} with type={}", targetClass, targetMethod.getName(),
          param.getType()
              .getName(),
          param.getName(), (payload == null) ? null : payload.getClass());
      parameters.add(null);
    }
    return parameters.toArray();
  }
}
