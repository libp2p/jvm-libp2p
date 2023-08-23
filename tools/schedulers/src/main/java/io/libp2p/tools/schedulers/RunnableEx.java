package io.libp2p.tools.schedulers;

/** The same as standard <code>Runnable</code> which can throw unchecked exception */
public interface RunnableEx {
  void run() throws Exception;
}
