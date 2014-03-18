package edu.jhu.hlt.fnparse.util;

import java.util.Iterator;

public class Take<T> implements Iterator<T> {

	private Iterator<T> base;
	private int remaining;
	
	public Take(Iterator<T> base, int howMany) {
		this.base = base;
		this.remaining = howMany;
	}

	@Override
	public boolean hasNext() {
		return remaining > 0 && base.hasNext();
	}

	@Override
	public T next() {
		return base.next();
	}

	@Override
	public void remove() {
		throw new UnsupportedOperationException();
	}
}
