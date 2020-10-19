package eu.europa.esig.dss.pades.validation;

import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.validation.AbstractSignatureIdentifierBuilder;
import eu.europa.esig.dss.validation.AdvancedSignature;

public class PAdESSignatureIdentifierBuilder extends AbstractSignatureIdentifierBuilder {

	public PAdESSignatureIdentifierBuilder(PAdESSignature signature) {
		super(signature);
	}

	@Override
	protected Object getCounterSignaturePosition(AdvancedSignature masterSignature) {
		throw new DSSException("Not supported in PAdES!");
	}

	@Override
	protected String getSignatureFilePosition() {
		PAdESSignature padesSignature = (PAdESSignature) signature;
		StringBuilder stringBuilder = new StringBuilder();
		for (String filedName : padesSignature.getPdfRevision().getFieldNames()) {
			stringBuilder.append(filedName);
		}
		return stringBuilder.toString();
	}

}