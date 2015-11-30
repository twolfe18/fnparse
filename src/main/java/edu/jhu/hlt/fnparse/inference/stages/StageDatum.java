package edu.jhu.hlt.fnparse.inference.stages;

import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList.LFgExample;

/**
 * Does one step in a pipeline.
 * An example would be the "frameId" stage:
 *   class FrameIdStageDatum extends StageDatum<Sentence, FNTagging>
 * 
 * Implementations of this interface should not include a large state (which
 * may not fit in memory). For example, it is OK for this to store a
 * FgInferencerFactory, but not the FactorGraph itself that would be
 * generated. That FactorGraph should be stored in the IDecodable upon
 * calling getDecodable().
 * 
 * Implementations of this interface should not memoize because caching and
 * memory management will be done by classes that call this class.
 * 
 * @param <Input> type of the data required for this stage to start its job.
 * @param <Intermediate> type of variables used for decoding.
 * @param <Output> type of the data produced by running inference and then
 *                 decoding this stage.
 */
public interface StageDatum<Input, Output> {

  public Input getInput();

  /**
   * If true, then can call getExample() and getGold(),
   * otherwise only getDecodable() should be called.
   */
  public boolean hasGold();

  /**
   * Should return null if !hasGold() and a non-null value otherwise.
   */
  public Output getGold();

  /** For training */
  public LFgExample getExample();

  /** For prediction */
  public IDecodable<Output> getDecodable();
}