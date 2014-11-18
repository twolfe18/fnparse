package edu.jhu.hlt.fnparse.agiga;

import edu.jhu.hlt.concrete.Communication;

/**
 * Made this because I can't get concrete-pipeline to compile in eclipse...
 * 
 * @author travis
 */
public interface DummyAnnotator {

  public void init();

  public Communication annotate(Communication c) throws Exception;
}
