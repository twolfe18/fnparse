package edu.jhu.hlt.fnparse.inference.stages;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleList;
import edu.jhu.hlt.fnparse.inference.stages.Stage.StageDatum;

/**
 * takes a List<StageDatum> and implements FgExamleList
 * 
 * @author travis
 */
public class StageDatumExampleList<I, O> implements FgExampleList {
	
	private final List<StageDatum<I, O>> data;
	
	public StageDatumExampleList(List<StageDatum<I, O>> data) {
		this.data = data;
	}

	@Override
	public Iterator<FgExample> iterator() {
		return new Iterator<FgExample>() {
			private Iterator<StageDatum<I, O>> iter = data.iterator();
			@Override
			public boolean hasNext() {
				return iter.hasNext();
			}
			@Override
			public FgExample next() {
				return iter.next().getExample();
			}
			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	@Override
	public FgExample get(int index) {
		return data.get(index).getExample();
	}
	
	public StageDatum<I, O> getStageDatum(int index) {
		return data.get(index);
	}
	
	public List<O> decodeAll() {
		List<O> out = new ArrayList<>();
		for (StageDatum<I, O> d : data)
			out.add(d.getDecodable().decode());
		return out;
	}
	
	public List<StageDatum<I, O>> getStageData() {
		return data;
	}

	@Override
	public int size() {
		return data.size();
	}
	
}
