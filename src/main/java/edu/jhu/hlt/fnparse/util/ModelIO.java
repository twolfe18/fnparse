package edu.jhu.hlt.fnparse.util;

import java.io.*;

import edu.jhu.gm.model.FgModel;
import edu.jhu.util.Alphabet;

public class ModelIO {

	public static void writeHumanReadable(FgModel model, Alphabet<String> featIdx, File f) {
		if(model == null || featIdx == null)
			throw new IllegalArgumentException();
		try {
			int n = model.getNumParams();
			assert n >= featIdx.size();
			double[] values = new double[n];
			model.updateDoublesFromModel(values);
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
			for(int i=0; i<featIdx.size(); i++) {
				String fName = featIdx.lookupObject(i);
				w.write(String.format("%f\t%s\n", values[i], fName));
			}
			w.close();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public static void writeBinary(FgModel model, File f) {
		try {
			int n = model.getNumParams();
			double[] values = new double[n];
			model.updateDoublesFromModel(values);
			DataOutputStream dos = new DataOutputStream(new FileOutputStream(f));
			dos.writeInt(n);
			for(int i=0; i<n; i++)
				dos.writeDouble(values[i]);
			dos.close();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public static FgModel readBinary(File f) {
		try {
			DataInputStream dis = new DataInputStream(new FileInputStream(f));
			int dimension = dis.readInt();
			FgModel model = new FgModel(dimension);
			double[] values = new double[dimension];
			for(int i=0; i<dimension; i++)
				values[i] = dis.readDouble();
			dis.close();
			model.updateModelFromDoubles(values);
			return model;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public static Alphabet<String> readAlphabet(File f) {
		try {
			Alphabet<String> alph = new Alphabet<String>();
			BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
			while(r.ready()) {
				String e = r.readLine().trim();
				alph.lookupIndex(e, true);
			}
			r.close();
			return alph;
		} catch (Exception e1) {
			throw new RuntimeException(e1);
		}
	}
	
	public static void writeAlphabet(Alphabet<String> alph, File f) {
		try {
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
			int n = alph.size();
			for(int i=0; i<n; i++) {
				String e = alph.lookupObject(i);
				assert !e.contains("\n") && e.equals(e.trim());
				w.write(e + "\n");
			}
			w.close();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
