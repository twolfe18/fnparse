package edu.jhu.hlt.fnparse.inference.stages;

/**
 * Basically a Future<Output>
 */
public interface IDecodable<Output> {
  public Output decode();
}