package org.snomed.snowstorm.core.util;

import com.google.common.collect.Sets;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.config.Config;
import org.snomed.snowstorm.core.data.domain.Concepts;
import org.snomed.snowstorm.core.data.domain.Description;
import org.snomed.snowstorm.rest.ControllerHelper;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;
import static org.snomed.snowstorm.core.data.domain.Concepts.GB_EN_LANG_REFSET;
import static org.snomed.snowstorm.core.data.domain.Concepts.US_EN_LANG_REFSET;

class DescriptionHelperTest {

	@Test
	void foldTerm() {
		HashSet<Character> charactersNotFolded = Sets.newHashSet('å', 'ä', 'ö', 'Å', 'Ä', 'Ö');

		// Swedish character ä not folded
		assertEquals("hjärta", DescriptionHelper.foldTerm("Hjärta", charactersNotFolded));

		// Swedish character é is folded
		assertEquals("lasegues test", DescriptionHelper.foldTerm("Laségues test", charactersNotFolded));

		// æ is folded to ae
		assertEquals("spaelsau sheep breed (organism) spaelsau", DescriptionHelper.foldTerm("Spælsau sheep breed (organism) Spælsau", charactersNotFolded));
	}

	@Test
	void combinedCharactersNotFolded() {
		HashSet<Character> charactersNotFolded = Sets.newHashSet('æ');

		// æ is not folded
		assertEquals("spælsau sheep breed (organism) spælsau", DescriptionHelper.foldTerm("Spælsau sheep breed (organism) Spælsau", charactersNotFolded));
	}

	@Test
	void testGetPtDescription() {
		String danishLanguageReferenceSet = "554461000005103";

		Set<Description> descriptions = Sets.newHashSet(
				// FSN
				new Description("Jet airplane, device (physical object)").setTypeId(Concepts.FSN),

				// EN-US PT
				new Description("Jet airplane").setTypeId(Concepts.SYNONYM).addLanguageRefsetMember(US_EN_LANG_REFSET, Concepts.PREFERRED),

				// EN-GB PT
				// ... British English aeroplane, from French aéroplane, from Ancient Greek ἀερόπλανος (aeróplanos, “wandering in air”).
				// I love the history of the English language!
				new Description("Jet aeroplane").setTypeId(Concepts.SYNONYM).addLanguageRefsetMember(GB_EN_LANG_REFSET, Concepts.PREFERRED),

				new Description("A1jetfly").setTypeId(Concepts.SYNONYM).setLang("da").addLanguageRefsetMember(danishLanguageReferenceSet, Concepts.PREFERRED, false),
				new Description("A2jetfly").setTypeId(Concepts.SYNONYM).setLang("da").addLanguageRefsetMember(danishLanguageReferenceSet, Concepts.PREFERRED, false),
				new Description("A3jetfly").setTypeId(Concepts.SYNONYM).setLang("da").addLanguageRefsetMember(danishLanguageReferenceSet, Concepts.PREFERRED, false),
				new Description("A4jetfly").setTypeId(Concepts.SYNONYM).setLang("da").addLanguageRefsetMember(danishLanguageReferenceSet, Concepts.PREFERRED, false),
				new Description("Bjetfly").setTypeId(Concepts.SYNONYM).setLang("da").addLanguageRefsetMember(danishLanguageReferenceSet, Concepts.PREFERRED),
				new Description("Cjetfly").setTypeId(Concepts.SYNONYM).setLang("da").addLanguageRefsetMember(danishLanguageReferenceSet, Concepts.ACCEPTABLE),
				new Description("D1jetfly").setTypeId(Concepts.SYNONYM).setLang("da").addLanguageRefsetMember(danishLanguageReferenceSet, Concepts.PREFERRED, false),
				new Description("D2jetfly").setTypeId(Concepts.SYNONYM).setLang("da").addLanguageRefsetMember(danishLanguageReferenceSet, Concepts.PREFERRED, false),
				new Description("D3jetfly").setTypeId(Concepts.SYNONYM).setLang("da").addLanguageRefsetMember(danishLanguageReferenceSet, Concepts.PREFERRED, false),
				new Description("D4jetfly").setTypeId(Concepts.SYNONYM).setLang("da").addLanguageRefsetMember(danishLanguageReferenceSet, Concepts.PREFERRED, false)
		);

		assertEquals("en-X-900000000000509007,en-X-900000000000508004,en", Config.DEFAULT_ACCEPT_LANG_HEADER);
		assertEquals("Jet airplane", getPtTerm(null, descriptions));
		assertEquals("Jet airplane", getPtTerm(Config.DEFAULT_ACCEPT_LANG_HEADER, descriptions));
		assertEquals("Jet aeroplane", getPtTerm("en-X-" + GB_EN_LANG_REFSET, descriptions));
		assertEquals("Fallback on US english defaults.", "Jet airplane", getPtTerm("en-X-" + danishLanguageReferenceSet, descriptions));
		assertEquals("Fallback on GB english because of header.", "Jet aeroplane", getPtTerm("en-X-" + danishLanguageReferenceSet + ",en-X-" + GB_EN_LANG_REFSET, descriptions));
		assertEquals("Bjetfly", getPtTerm("da-X-" + danishLanguageReferenceSet, descriptions));
		assertEquals("Bjetfly", getPtTerm("da", descriptions));
	}

	@Test
	void testPickFSNUsingLangOnlyWhenNoLangRefset() {
		final HashSet<Description> descriptionWithNoAcceptability = Sets.newHashSet(new Description("Neoplasm and/or hamartoma reference set (foundation metadata concept)").setTypeId(Concepts.FSN));
		assertEquals("Pick FSN with correct language when only language requested.",
				"Neoplasm and/or hamartoma reference set (foundation metadata concept)", getFSNTerm("en", descriptionWithNoAcceptability));
		assertEquals("Pick FSN with correct language when language dialect requested because 'en' is always included as fallback.",
				"Neoplasm and/or hamartoma reference set (foundation metadata concept)", getFSNTerm("en-X-900000000000509007", descriptionWithNoAcceptability));
	}

	private String getPtTerm(String acceptLanguageHeader, Set<Description> descriptions) {
		return DescriptionHelper.getPtDescription(descriptions, ControllerHelper.parseAcceptLanguageHeaderWithDefaultFallback(acceptLanguageHeader)).orElseGet(Description::new).getTerm();
	}

	private String getFSNTerm(String acceptLanguageHeader, Set<Description> descriptions) {
		return DescriptionHelper.getFsnDescription(descriptions, ControllerHelper.parseAcceptLanguageHeaderWithDefaultFallback(acceptLanguageHeader)).orElseGet(Description::new).getTerm();
	}
}
