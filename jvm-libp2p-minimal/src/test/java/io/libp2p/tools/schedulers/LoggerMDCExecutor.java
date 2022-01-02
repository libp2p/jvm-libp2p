package io.libp2p.tools.schedulers;

import org.apache.logging.log4j.ThreadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public class LoggerMDCExecutor implements Executor {

  private final List<String> mdcKeys = new ArrayList<>();
  private final List<Supplier<String>> mdcValueSuppliers = new ArrayList<>();
  private final Executor delegateExecutor;

  public LoggerMDCExecutor() {
    this(Runnable::run);
  }

  public LoggerMDCExecutor(Executor delegateExecutor) {
    this.delegateExecutor = delegateExecutor;
  }

  public LoggerMDCExecutor add(String mdcKey, Supplier<String> mdcValueSupplier) {
    mdcKeys.add(mdcKey);
    mdcValueSuppliers.add(mdcValueSupplier);
    return this;
  }

  @Override
  public void execute(Runnable command) {
    List<String> oldValues = new ArrayList<>(mdcKeys.size());
    for (int i = 0; i < mdcKeys.size(); i++) {
      oldValues.add(ThreadContext.get(mdcKeys.get(i)));
      ThreadContext.put(mdcKeys.get(i), mdcValueSuppliers.get(i).get());
    }
    delegateExecutor.execute(command);
    for (int i = 0; i < mdcKeys.size(); i++) {
      if (oldValues.get(i) == null) {
        ThreadContext.remove(mdcKeys.get(i));
      } else {
        ThreadContext.put(mdcKeys.get(i), oldValues.get(i));
      }
    }
  }
}
