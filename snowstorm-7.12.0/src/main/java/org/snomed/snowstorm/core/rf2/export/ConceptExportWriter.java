package org.snomed.snowstorm.core.rf2.export;

import org.snomed.snowstorm.core.data.domain.Concept;
import org.snomed.snowstorm.core.rf2.RF2Constants;

import java.io.BufferedWriter;
import java.io.IOException;

class ConceptExportWriter extends ExportWriter<Concept> {

	ConceptExportWriter(BufferedWriter bufferedWriter) {
		super(bufferedWriter);
	}

	void writeHeader() throws IOException {
		bufferedWriter.write(RF2Constants.CONCEPT_HEADER);
		writeNewLine();
	}

	void flush() {
		try {
			for (Concept concept : componentBuffer) {
				bufferedWriter.write(concept.getConceptId());
				bufferedWriter.write(TAB);
				bufferedWriter.write(concept.getEffectiveTimeI() != null ? concept.getEffectiveTimeI().toString() :  getTransientEffectiveTime());
				bufferedWriter.write(TAB);
				bufferedWriter.write(concept.isActive() ? "1" : "0");
				bufferedWriter.write(TAB);
				bufferedWriter.write(concept.getModuleId());
				bufferedWriter.write(TAB);
				bufferedWriter.write(concept.getDefinitionStatusId());
				writeNewLine();
			}
			componentBuffer.clear();
		} catch (IOException e) {
			throw new ExportException("Failed to write Concept to RF2 file.", e);
		}
	}

}
