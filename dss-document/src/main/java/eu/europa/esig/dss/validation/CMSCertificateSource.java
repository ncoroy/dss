/**
 * DSS - Digital Signature Services
 * Copyright (C) 2015 European Commission, provided under the CEF programme
 * 
 * This file is part of the "DSS - Digital Signature Services" project.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package eu.europa.esig.dss.validation;

import static eu.europa.esig.dss.spi.OID.attributeCertificateRefsOid;
import static org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_ets_certValues;
import static org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_ets_certificateRefs;
import static org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signingCertificate;
import static org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signingCertificateV2;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ess.ESSCertID;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.OtherCertID;
import org.bouncycastle.asn1.ess.SigningCertificate;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.europa.esig.dss.enumerations.CertificateOrigin;
import eu.europa.esig.dss.enumerations.CertificateRefOrigin;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.Digest;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.DSSASN1Utils;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.x509.CertificateIdentifier;
import eu.europa.esig.dss.spi.x509.CertificatePool;
import eu.europa.esig.dss.spi.x509.CertificateRef;
import eu.europa.esig.dss.utils.Utils;

@SuppressWarnings("serial")
public abstract class CMSCertificateSource extends SignatureCertificateSource {

	private static final Logger LOG = LoggerFactory.getLogger(CMSCertificateSource.class);
	
	private final transient CMSSignedData cmsSignedData;
	private final transient SignerInformation currentSignerInformation;
	
	/**
	 * The constructor to instantiate a CMSCertificateSource. Allows to define a
	 * used signerInformation.
	 * 
	 * @param cmsSignedData            {@link CMSSignedData}
	 * @param currentSignerInformation the current {@link SignerInformation}
	 *                                 extracted from cmsSignedData
	 * @param certPool                 {@link CertificatePool}
	 */
	protected CMSCertificateSource(final CMSSignedData cmsSignedData, final SignerInformation currentSignerInformation, final CertificatePool certPool) {
		super(certPool);
		Objects.requireNonNull(cmsSignedData, "CMS SignedData is null, it must be provided!");
		Objects.requireNonNull(currentSignerInformation, "currentSignerInformation is null, it must be provided!");
		this.cmsSignedData = cmsSignedData;
		this.currentSignerInformation = currentSignerInformation;

		extractCertificateIdentifiers();
		extractSignedCertificates();
		extractSigningCertificateReferences();

		extractCertificateValues();
		extractCertificateRefsFromUnsignedAttribute(id_aa_ets_certificateRefs, CertificateRefOrigin.COMPLETE_CERTIFICATE_REFS);
		extractCertificateRefsFromUnsignedAttribute(attributeCertificateRefsOid, CertificateRefOrigin.ATTRIBUTE_CERTIFICATE_REFS);
	}

	private void extractCertificateIdentifiers() {
		CertificateIdentifier currentCertificateIdentifier = DSSASN1Utils.toIssuerSerialInfo(currentSignerInformation.getSID());
		boolean found = false;
		Collection<SignerInformation> signers = cmsSignedData.getSignerInfos().getSigners();
		for (SignerInformation signerInformation : signers) {
			CertificateIdentifier certificateIdentifier = DSSASN1Utils.toIssuerSerialInfo(signerInformation.getSID());
			if (certificateIdentifier.isEquivalent(currentCertificateIdentifier)) {
				certificateIdentifier.setCurrent(true);
				found = true;
			}
			addCertificateIdentifier(certificateIdentifier, CertificateOrigin.SIGNED_DATA);
		}
		if (!found) {
			LOG.warn("SID not found in SignerInfos");
			currentCertificateIdentifier.setCurrent(true);
			addCertificateIdentifier(currentCertificateIdentifier, CertificateOrigin.SIGNED_DATA);
		}
	}

	private void extractSignedCertificates() {
		try {
			final Collection<X509CertificateHolder> x509CertificateHolders = cmsSignedData.getCertificates().getMatches(null);
			for (final X509CertificateHolder x509CertificateHolder : x509CertificateHolders) {
				addCertificate(DSSASN1Utils.getCertificate(x509CertificateHolder), CertificateOrigin.SIGNED_DATA);
			}
		} catch (Exception e) {
			LOG.warn("Cannot extract certificates from CMS Signed Data : {}", e.getMessage());
		}
	}

	public void extractSigningCertificateReferences() {
		AttributeTable signedAttributes = currentSignerInformation.getSignedAttributes();
		if (signedAttributes != null && signedAttributes.size() > 0) {
			final Attribute signingCertificateAttributeV1 = signedAttributes.get(id_aa_signingCertificate);
			if (signingCertificateAttributeV1 != null) {
				extractSigningCertificateV1(signingCertificateAttributeV1);
			}
			final Attribute signingCertificateAttributeV2 = signedAttributes.get(id_aa_signingCertificateV2);
			if (signingCertificateAttributeV2 != null) {
				extractSigningCertificateV2(signingCertificateAttributeV2);
			}
		}
	}

	private void extractSigningCertificateV1(Attribute attribute) {
		final ASN1Set attrValues = attribute.getAttrValues();
		for (int ii = 0; ii < attrValues.size(); ii++) {
			final ASN1Encodable asn1Encodable = attrValues.getObjectAt(ii);
			try {
				final SigningCertificate signingCertificate = SigningCertificate.getInstance(asn1Encodable);
				if (signingCertificate != null) {
					extractESSCertIDs(signingCertificate.getCerts(), CertificateRefOrigin.SIGNING_CERTIFICATE);
				} else {
					LOG.warn("SigningCertificate attribute is null");
				}
			} catch (Exception e) {
				LOG.warn("SigningCertificate attribute '{}' is not well defined!", Utils.toBase64(DSSASN1Utils.getDEREncoded(asn1Encodable)));
			}
		}
	}

	private void extractESSCertIDs(final ESSCertID[] essCertIDs, CertificateRefOrigin origin) {
		for (final ESSCertID essCertID : essCertIDs) {
			CertificateRef certRef = new CertificateRef();

			final byte[] certHash = essCertID.getCertHash();
			if (Utils.isArrayNotEmpty(certHash)) {
				certRef.setCertDigest(new Digest(DigestAlgorithm.SHA1, certHash));
				if (LOG.isDebugEnabled()) {
					LOG.debug("Found Certificate Hash in signingCertificateAttributeV1 {} with algorithm {}", Utils.toHex(certHash), DigestAlgorithm.SHA1);
				}
			}
			certRef.setCertificateIdentifier(DSSASN1Utils.toCertificateIdentifier(essCertID.getIssuerSerial()));
			certRef.setOrigin(origin);
			addCertificateRef(certRef, origin);
		}
	}

	private void extractSigningCertificateV2(Attribute attribute) {
		final ASN1Set attrValues = attribute.getAttrValues();
		for (int ii = 0; ii < attrValues.size(); ii++) {
			final ASN1Encodable asn1Encodable = attrValues.getObjectAt(ii);
			try {
				final SigningCertificateV2 signingCertificate = SigningCertificateV2.getInstance(asn1Encodable);
				if (signingCertificate != null) {
					extractESSCertIDv2s(signingCertificate.getCerts(), CertificateRefOrigin.SIGNING_CERTIFICATE);
				} else {
					LOG.warn("SigningCertificateV2 attribute is null");
				}
			} catch (Exception e) {
				LOG.warn("SigningCertificateV2 attribute '{}' is not well defined!", Utils.toBase64(DSSASN1Utils.getDEREncoded(asn1Encodable)));
			}
		}
	}

	private void extractESSCertIDv2s(ESSCertIDv2[] essCertIDv2s, CertificateRefOrigin origin) {
		for (final ESSCertIDv2 essCertIDv2 : essCertIDv2s) {
			CertificateRef certRef = new CertificateRef();
			final DigestAlgorithm digestAlgorithm = DigestAlgorithm.forOID(essCertIDv2.getHashAlgorithm().getAlgorithm().getId());
			final byte[] certHash = essCertIDv2.getCertHash();
			certRef.setCertDigest(new Digest(digestAlgorithm, certHash));
			if (LOG.isDebugEnabled()) {
				LOG.debug("Found Certificate Hash in SigningCertificateV2 {} with algorithm {}", Utils.toHex(certHash), digestAlgorithm);
			}
			certRef.setCertificateIdentifier(DSSASN1Utils.toCertificateIdentifier(essCertIDv2.getIssuerSerial()));
			certRef.setOrigin(origin);

			addCertificateRef(certRef, origin);
		}
	}

	private void extractCertificateValues() {
		AttributeTable unsignedAttributes = currentSignerInformation.getUnsignedAttributes();
		if (unsignedAttributes != null) {
			Attribute attribute = unsignedAttributes.get(id_aa_ets_certValues);
			if (attribute != null) {
				final ASN1Sequence seq = (ASN1Sequence) attribute.getAttrValues().getObjectAt(0);
				for (int ii = 0; ii < seq.size(); ii++) {
					try {
						final Certificate cs = Certificate.getInstance(seq.getObjectAt(ii));
						addCertificate(DSSUtils.loadCertificate(cs.getEncoded()), CertificateOrigin.CERTIFICATE_VALUES);
					} catch (Exception e) {
						LOG.warn("Unable to parse encapsulated certificate : {}", e.getMessage());
					}
				}
			}
		}
	}

	private void extractCertificateRefsFromUnsignedAttribute(ASN1ObjectIdentifier attributeOid, CertificateRefOrigin origin) {
		AttributeTable unsignedAttributes = currentSignerInformation.getUnsignedAttributes();
		if (unsignedAttributes != null) {
			Attribute attribute = unsignedAttributes.get(attributeOid);
			if (attribute != null) {
				final ASN1Sequence seq = (ASN1Sequence) attribute.getAttrValues().getObjectAt(0);
				for (int ii = 0; ii < seq.size(); ii++) {
					try {
						OtherCertID otherCertId = OtherCertID.getInstance(seq.getObjectAt(ii));
						CertificateRef certRef = DSSASN1Utils.getCertificateRef(otherCertId);
						certRef.setOrigin(origin);
						addCertificateRef(certRef, origin);
					} catch (Exception e) {
						LOG.warn("Unable to parse encapsulated OtherCertID : {}", e.getMessage());
					}
				}
			}
		}
	}

	public CandidatesForSigningCertificate getCandidatesForSigningCertificate(CertificateToken providedSigningCertificateToken) {
		CandidatesForSigningCertificate candidates = new CandidatesForSigningCertificate();

		CertificateIdentifier currentCertificateIdentifier = getCurrentCertificateIdentifier();
		CertificateToken certificate = getCertificateToken(currentCertificateIdentifier);
		if (certificate == null && providedSigningCertificateToken != null) {
			LOG.info("Use the provided signing certificate");
			certificate = providedSigningCertificateToken;
		}

		CertificateValidity certificateValidity = null;
		if (certificate != null) {
			certificateValidity = new CertificateValidity(certificate);
		} else {
			certificateValidity = new CertificateValidity(currentCertificateIdentifier);
		}

		List<CertificateRef> signingCertRefs = getSigningCertificateRefs();
		boolean onlyOneSigningCert = Utils.collectionSize(signingCertRefs) == 1;
		certificateValidity.setAttributePresent(onlyOneSigningCert);
		if (onlyOneSigningCert) {
			CertificateRef signingCertRef = signingCertRefs.iterator().next();
			CertificateIdentifier sigCertIdentifier = signingCertRef.getCertificateIdentifier();
			Digest certDigest = signingCertRef.getCertDigest();
			certificateValidity.setDigestPresent(certDigest != null);

			if (certificate != null) {
				byte[] certificateDigest = certificate.getDigest(certDigest.getAlgorithm());
				certificateValidity.setDigestEqual(Arrays.equals(certificateDigest, certDigest.getValue()));
			}

			if (sigCertIdentifier != null) {
				if (certificate != null) {
					certificateValidity.setSerialNumberEqual(certificate.getSerialNumber().equals(sigCertIdentifier.getSerialNumber()));
					certificateValidity.setDistinguishedNameEqual(
							DSSASN1Utils.x500PrincipalAreEquals(certificate.getIssuerX500Principal(), sigCertIdentifier.getIssuerName()));
				} else {
					certificateValidity.setSerialNumberEqual(currentCertificateIdentifier.getSerialNumber().equals(sigCertIdentifier.getSerialNumber()));
					certificateValidity.setDistinguishedNameEqual(
							DSSASN1Utils.x500PrincipalAreEquals(currentCertificateIdentifier.getIssuerName(), sigCertIdentifier.getIssuerName()));
				}
				certificateValidity.setSignerIdMatch(currentCertificateIdentifier.isEquivalent(sigCertIdentifier));
			}
		}

		candidates.add(certificateValidity);
		candidates.setTheCertificateValidity(certificateValidity);

		return candidates;
	}

}
