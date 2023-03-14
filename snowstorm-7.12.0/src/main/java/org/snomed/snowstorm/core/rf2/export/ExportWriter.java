package org.snomed.snowstorm.core.rf2.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public abstract class ExportWriter<T> implements AutoCloseable {

	private static final int FLUSH_SIZE = 1000;
	static final String TAB = "\t";

	final BufferedWriter bufferedWriter;
	final List<T> componentBuffer;
	private int contentLinesWritten;
	private String transientEffectiveTime = "";

	ExportWriter(BufferedWriter bufferedWriter) {
		this.bufferedWriter = bufferedWriter;
		componentBuffer = new ArrayList<>();
	}

	abstract void writeHeader() throws IOException;

	void write(T component) {
		componentBuffer.add(component);
		contentLinesWritten++;
		if (componentBuffer.size() == FLUSH_SIZE) {
			flush();
		}
	}

	public void writeNewLine() throws IOException {
		bufferedWriter.write("\r\n");
	}

	abstract void flush();

	@Override
	public void close() throws IOException {
		flush();
		bufferedWriter.flush();
	}

	public int getContentLinesWritten() {
		return contentLinesWritten;
	}

	public void setTransientEffectiveTime(String transientEffectiveTime) {
		if (transientEffectiveTime != null) {
			this.transientEffectiveTime = transientEffectiveTime;
		}
	}

	public String getTransientEffectiveTime() {
		return transientEffectiveTime;
	}
}
