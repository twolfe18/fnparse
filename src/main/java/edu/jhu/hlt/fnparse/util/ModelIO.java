package edu.jhu.hlt.fnparse.util;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import edu.jhu.gm.model.FgModel;
import edu.jhu.util.Alphabet;

public class ModelIO {

	public static boolean preventOverwrites = false;

	public static void writeHumanReadable(FgModel model, Alphabet<String> featIdx, File f, boolean outputZeroFeatures) {
		if(preventOverwrites && f.isFile())
			throw new IllegalArgumentException(f.getPath() + " is already a file");
		if(model == null || featIdx == null)
			throw new IllegalArgumentException();
		try {
			double[] values = new double[model.getNumParams()];
			model.updateDoublesFromModel(values);
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
			int n = Math.min(model.getNumParams(), featIdx.size());
			for (int i = 0; i < n; i++) {
				if (!outputZeroFeatures && Math.abs(values[i]) < 1e-5)
					continue;
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
		if(preventOverwrites && f.isFile())
			throw new IllegalArgumentException(f.getPath() + " is already a file");
		try {
			DataOutputStream dos = new DataOutputStream(new FileOutputStream(f));
			writeBinary(model, dos);
			dos.close();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static void writeBinary(FgModel model, DataOutputStream dos) throws IOException {
		int n = model.getNumParams();
		double[] values = new double[n];
		model.updateDoublesFromModel(values);
        dos.writeInt(n);
        for(int i=0; i<n; i++)
        	dos.writeDouble(values[i]);
	}

	public static FgModel readBinary(File f) {
		if(!f.isFile())
			throw new IllegalArgumentException(f.getPath() + " is not a file");
		try {
			DataInputStream dis = new DataInputStream(new FileInputStream(f));
			FgModel model = readBinary(dis);
			dis.close();
			return model;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static FgModel readBinary(DataInputStream dis) throws IOException {
		int dimension = dis.readInt();
		FgModel model = new FgModel(dimension);
		double[] values = new double[dimension];
		for(int i=0; i<dimension; i++)
			values[i] = dis.readDouble();
		model.updateModelFromDoubles(values);
		return model;
	}

	/**
	 * @return an alphabet from a file which is guaranteed to not be growing.
	 */
	public static Alphabet<String> readAlphabet(File f) {
		if(!f.isFile())
			throw new IllegalArgumentException(f.getPath() + " is not a file");
		try {
			Timer t = Timer.start("");
			Alphabet<String> alph = new Alphabet<String>();
			InputStream is = new FileInputStream(f);
			if(f.getName().toLowerCase().endsWith(".gz"))
				is = new GZIPInputStream(is);
			BufferedReader r = new BufferedReader(new InputStreamReader(is));
			while(r.ready()) {
				String e = r.readLine();
				alph.lookupIndex(e, true);
			}
			r.close();
			alph.stopGrowth();
			t.stop();
			System.out.printf("[ModelIO.readAlphabet] read %d entries from %s in %.1f seconds\n",
					alph.size(), f.getPath(), t.totalTimeInSeconds());
			return alph;
		} catch (Exception e1) {
			throw new RuntimeException(e1);
		}
	}

	public static void writeAlphabet(Alphabet<String> alph, File f) {
		if(preventOverwrites && f.isFile())
			throw new IllegalArgumentException(f.getPath() + " is already a file");
		try {
			Timer t = Timer.start("");
			OutputStream os = new FileOutputStream(f);
			if(f.getName().toLowerCase().endsWith(".gz"))
				os = new GZIPOutputStream(os);
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os));
			int n = alph.size();
			for(int i=0; i<n; i++) {
				String e = alph.lookupObject(i);
				if(e.contains("\n"))
					throw new RuntimeException("this feature name contains my delimiter (newline): " + e);
				w.write(e);
				w.write("\n");
			}
			w.close();
			t.stop();
			System.out.printf("[ModelIO.writeAlphabet] wrote %d entries to %s in %.1f seconds\n",
					alph.size(), f.getPath(), t.totalTimeInSeconds());
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
