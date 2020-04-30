package eu.europa.esig.dss.cades.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.bouncycastle.asn1.BERTags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import eu.europa.esig.dss.cades.CAdESSignatureParameters;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.enumerations.SignaturePackaging;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.identifier.EncapsulatedRevocationTokenIdentifier;
import eu.europa.esig.dss.signature.DocumentSignatureService;
import eu.europa.esig.dss.spi.x509.revocation.OfflineRevocationSource;
import eu.europa.esig.dss.spi.x509.revocation.crl.CRL;
import eu.europa.esig.dss.validation.AdvancedSignature;

@Tag("slow")
public class CAdESWithPemEncodedCrlTest extends AbstractCAdESTestSignature {

	private DocumentSignatureService<CAdESSignatureParameters, CAdESTimestampParameters> service;
	private CAdESSignatureParameters signatureParameters;
	private DSSDocument documentToSign;

	private static Stream<Arguments> data() {
		Object[] objects = { SignatureLevel.CAdES_BASELINE_B, SignatureLevel.CAdES_BASELINE_T, 
				SignatureLevel.CAdES_BASELINE_LT, SignatureLevel.CAdES_BASELINE_LTA };

		Collection<Arguments> dataToRun = new ArrayList<>();
		for (Object obj : objects) {
			dataToRun.add(Arguments.of(obj));
		}
		return dataToRun.stream();
	}

	@BeforeEach
	public void init() throws Exception {
		documentToSign = new InMemoryDocument("Hello World!".getBytes());
		
		signatureParameters = new CAdESSignatureParameters();
		signatureParameters.setSigningCertificate(getSigningCert());
		signatureParameters.setCertificateChain(getCertificateChain());
		signatureParameters.setSignaturePackaging(SignaturePackaging.ENVELOPING);

		service = new CAdESService(getCompleteCertificateVerifier());
		service.setTspSource(getGoodTsa());
	}

	@ParameterizedTest(name = "SignatureLevel {index} : {0}")
	@MethodSource("data")
	public void test(SignatureLevel level) {
		signatureParameters.setSignatureLevel(level);
		super.signAndVerify();
	}
	
	@Override
	public void signAndVerify() {
		// do nothing
	}
	
	@Override
	protected void checkAdvancedSignatures(List<AdvancedSignature> signatures) {
		super.checkAdvancedSignatures(signatures);
		
		assertEquals(1, signatures.size());
		AdvancedSignature advancedSignature = signatures.get(0);
		OfflineRevocationSource<CRL> crlSource = advancedSignature.getCRLSource();
		Set<EncapsulatedRevocationTokenIdentifier> allRevocationBinaries = crlSource.getAllRevocationBinaries();
		for (EncapsulatedRevocationTokenIdentifier identifier : allRevocationBinaries) {
			assertTrue(isDerEncoded(identifier.getBinaries()));
		}
	}
	
	private boolean isDerEncoded(byte[] binaries) {
		return binaries != null && binaries.length > 0 && (BERTags.SEQUENCE | BERTags.CONSTRUCTED) == binaries[0];
	}

	@Override
	protected DocumentSignatureService<CAdESSignatureParameters, CAdESTimestampParameters> getService() {
		return service;
	}

	@Override
	protected CAdESSignatureParameters getSignatureParameters() {
		return signatureParameters;
	}

	@Override
	protected DSSDocument getDocumentToSign() {
		return documentToSign;
	}

	@Override
	protected String getSigningAlias() {
		return GOOD_USER_WITH_PEM_CRL;
	}
	
}
