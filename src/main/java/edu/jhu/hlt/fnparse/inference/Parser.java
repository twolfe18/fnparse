package edu.jhu.hlt.fnparse.inference;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;

/**
 * Parent class for classes that do parsing. Training is not captured here and
 * is assumed to be deriving-class-specific.
 * 
 * @author travis
 */
public interface Parser extends HasFeatureAlphabet {

	/**
	 * Parse some sentences. gold may be null, otherwise the elements in gold
	 * should match up with the input sentences (same length lists).
	 */
	public List<FNParse> parse(List<Sentence> sentences, List<FNParse> gold);
}
