package org.snomed.snowstorm.mrcm;

import io.kaicode.elasticvc.api.BranchCriteria;
import io.kaicode.elasticvc.api.CommitListener;
import io.kaicode.elasticvc.api.VersionControlHelper;
import io.kaicode.elasticvc.domain.Commit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.langauges.ecl.ECLException;
import org.snomed.langauges.ecl.ECLQueryBuilder;
import org.snomed.langauges.ecl.domain.expressionconstraint.ExpressionConstraint;
import org.snomed.langauges.ecl.domain.expressionconstraint.RefinedExpressionConstraint;
import org.snomed.langauges.ecl.domain.expressionconstraint.SubExpressionConstraint;
import org.snomed.snowstorm.core.data.domain.Concepts;
import org.snomed.snowstorm.core.data.domain.ConcreteValue;
import org.snomed.snowstorm.core.data.domain.ReferenceSetMember;
import org.snomed.snowstorm.core.data.services.ReferenceSetMemberService;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.core.data.services.pojo.MemberSearchRequest;
import org.snomed.snowstorm.core.util.TimerUtil;
import org.snomed.snowstorm.mrcm.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.kaicode.elasticvc.api.ComponentService.LARGE_PAGE;

@Service
public class MRCMLoader implements CommitListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(MRCMLoader.class);

    private final Map<String, MRCM> cache = new HashMap<>();

    @Autowired
    private ECLQueryBuilder eclQueryBuilder;

    @Autowired
	@Lazy
    private ReferenceSetMemberService memberService;

    @Autowired
    private VersionControlHelper versionControlHelper;

    @Override
    public void preCommitCompletion(final Commit commit) throws IllegalStateException {
        this.cache.remove(commit.getBranch().getPath());
    }

    /**
     * Retrieve the latest MRCM for the given branch.
     *
     * @param branchPath     The branch to read MRCM data from.
     * @param branchCriteria The branch criteria to use for querying the target branch.
     * @return The MRCM for the given branch.
     * @throws ServiceException When there is an issue reading MRCM.
     */
    // TODO: Make this work for MRCM extensions. Ask Guillermo how he is extending the MRCM in Extensions TermMed are maintaining.
    public MRCM loadActiveMRCM(String branchPath, BranchCriteria branchCriteria) throws ServiceException {
        final TimerUtil timer = new TimerUtil("MRCM");
        final List<Domain> domains = getDomains(branchPath, branchCriteria, timer);
        final List<AttributeDomain> attributeDomains = getAttributeDomains(branchPath, branchCriteria, timer);
        final List<AttributeRange> attributeRanges = getAttributeRanges(branchPath, branchCriteria, timer);

        return new MRCM(domains, attributeDomains, attributeRanges);
    }

    /**
     * Retrieve the latest MRCM for the given branch. If the MRCM has been read
     * for the given branch, then the data is read from an internal cache. The cache will
     * subsequently be cleared whenever the MRCM is updated.
     *
     * @param branchPath The branch to read MRCM data from.
     * @return The MRCM for the given branch.
     * @throws ServiceException When there is an issue reading MRCM.
     */
    public MRCM loadActiveMRCMFromCache(String branchPath) throws ServiceException {
        LOGGER.debug("Checking cache for MRCM.");
        final MRCM cachedMRCM = cache.get(branchPath);
        if (cachedMRCM != null) {
            LOGGER.debug("MRCM present in cache.");
            return cachedMRCM;
        }
        LOGGER.debug("MRCM not present in cache; loading MRCM.");

        final BranchCriteria branchCriteria = versionControlHelper.getBranchCriteria(branchPath);
        final TimerUtil timer = new TimerUtil("MRCM");
        final List<Domain> domains = getDomains(branchPath, branchCriteria, timer);
        final List<AttributeDomain> attributeDomains = getAttributeDomains(branchPath, branchCriteria, timer);
        final List<AttributeRange> attributeRanges = getAttributeRanges(branchPath, branchCriteria, timer);
        final MRCM mrcm = new MRCM(domains, attributeDomains, attributeRanges);

        cache.putIfAbsent(branchPath, mrcm);
        return mrcm;
    }

    private List<Domain> getDomains(final String branchPath,
                                    final BranchCriteria branchCriteria,
                                    final TimerUtil timer) throws ServiceException {
        List<ReferenceSetMember> domainMembers = memberService.findMembers(branchPath, branchCriteria,
                new MemberSearchRequest().referenceSet(Concepts.REFSET_MRCM_DOMAIN_INTERNATIONAL), LARGE_PAGE).getContent();

        List<Domain> domains = new ArrayList<>();
        for (ReferenceSetMember member : domainMembers) {
            // id	effectiveTime	active	moduleId	refsetId	referencedComponentId	domainConstraint	parentDomain
            // proximalPrimitiveConstraint	proximalPrimitiveRefinement	domainTemplateForPrecoordination	domainTemplateForPostcoordination	guideURL
            if (member.isActive()) {
                domains.add(new Domain(
                        member.getMemberId(),
                        member.getEffectiveTime(),
                        member.isActive(),
                        member.getReferencedComponentId(),
                        getConstraint(member, "domainConstraint"),
                        member.getAdditionalField("parentDomain"),
                        getConstraint(member, "proximalPrimitiveConstraint"),
                        member.getAdditionalField("proximalPrimitiveRefinement"),
                        member.getAdditionalField("domainTemplateForPrecoordination"),
                        member.getAdditionalField("domainTemplateForPostcoordination")
                ));
            }
        }
        timer.checkpoint("Load domains");

        return domains;
    }

    private List<AttributeDomain> getAttributeDomains(final String branchPath,
                                                      final BranchCriteria branchCriteria,
                                                      final TimerUtil timer) throws ServiceException {
        List<ReferenceSetMember> attributeDomainMembers = memberService.findMembers(branchPath, branchCriteria,
                new MemberSearchRequest().referenceSet(Concepts.REFSET_MRCM_ATTRIBUTE_DOMAIN_INTERNATIONAL), LARGE_PAGE).getContent();

        List<AttributeDomain> attributeDomains = new ArrayList<>();
        for (ReferenceSetMember member : attributeDomainMembers) {
            // id	effectiveTime	active	moduleId	refsetId	referencedComponentId	domainId
            // grouped	attributeCardinality	attributeInGroupCardinality	ruleStrengthId	contentTypeId
            if (member.isActive()) {
                attributeDomains.add(new AttributeDomain(
                        member.getMemberId(),
                        member.getEffectiveTime(),
                        member.isActive(),
                        member.getReferencedComponentId(),
                        member.getAdditionalField("domainId"),
                        "1".equals(member.getAdditionalField("grouped")),
                        new Cardinality(member.getAdditionalField("attributeCardinality")),
                        new Cardinality(member.getAdditionalField("attributeInGroupCardinality")),
                        RuleStrength.lookupByConceptId(member.getAdditionalField("ruleStrengthId")),
                        ContentType.lookupByConceptId(member.getAdditionalField("contentTypeId"))
                ));
            }
        }
        timer.checkpoint("Load attribute domains");

        return attributeDomains;
    }

    private List<AttributeRange> getAttributeRanges(final String branchPath,
                                                    final BranchCriteria branchCriteria,
                                                    final TimerUtil timer) {
        List<ReferenceSetMember> attributeRangeMembers = memberService.findMembers(branchPath, branchCriteria,
                new MemberSearchRequest().referenceSet(Concepts.REFSET_MRCM_ATTRIBUTE_RANGE_INTERNATIONAL), LARGE_PAGE).getContent();

        List<AttributeRange> attributeRanges = new ArrayList<>();
        for (ReferenceSetMember member : attributeRangeMembers) {
            // id	effectiveTime	active	moduleId	refsetId	referencedComponentId	rangeConstraint	attributeRule	ruleStrengthId	contentTypeId
            if (member.isActive()) {
                String rangeConstraint = member.getAdditionalField("rangeConstraint");
                attributeRanges.add(new AttributeRange(
                        member.getMemberId(),
                        member.getEffectiveTime(),
                        member.isActive(),
                        member.getReferencedComponentId(),
                        rangeConstraint,
                        member.getAdditionalField("attributeRule"),
                        RuleStrength.lookupByConceptId(member.getAdditionalField("ruleStrengthId")),
                        ContentType.lookupByConceptId(member.getAdditionalField("contentTypeId")),
                        getConcreteValueDataType(rangeConstraint)
                ));
            }
        }
        timer.checkpoint("Load attribute ranges");

        return attributeRanges;
    }

    private Constraint getConstraint(ReferenceSetMember mrcmMember, String fieldName) throws ServiceException {
        String constraint = mrcmMember.getAdditionalField(fieldName);
        if (constraint == null || constraint.isEmpty()) {
            LOGGER.warn("No constraint found for '{}' in member {}.", fieldName, mrcmMember.getMemberId());
            return null;
        }
        try {
            String simplifiedConstraint = extractInitialSimpleConstraint(constraint);
            ExpressionConstraint ecl = eclQueryBuilder.createQuery(simplifiedConstraint);
            if (ecl instanceof SubExpressionConstraint) {
                SubExpressionConstraint sub = (SubExpressionConstraint) ecl;
                return new Constraint(constraint, sub.getConceptId(), sub.getOperator());
            } else if (ecl instanceof RefinedExpressionConstraint) {
                RefinedExpressionConstraint refined = (RefinedExpressionConstraint) ecl;
                SubExpressionConstraint sub = refined.getSubexpressionConstraint();
                return new Constraint(constraint, sub.getConceptId(), sub.getOperator());
            } else {
                LOGGER.error("Unable to process MRCM constraint '{}' in member {}.", constraint, mrcmMember.getMemberId());
            }
        } catch (ECLException e) {
            throw new ServiceException(String.format("Error parsing ECL in field %s of MRCM member %s, ECL: %s", fieldName, mrcmMember.getMemberId(), constraint), e);
        }
        return null;
    }

    /**
     * Returns the very first part of the constraint should be an operator and a single focus concept.
     */
    private String extractInitialSimpleConstraint(String expressionTemplate) {
        String simplifiedConstraint = "*";
        Pattern pattern = Pattern.compile("^([^0-9]*[0-9]+).*");
        Matcher matcher = pattern.matcher(expressionTemplate);
        if (matcher.matches()) {
            simplifiedConstraint = matcher.group(1);
        }
        return simplifiedConstraint;
    }

    private ConcreteValue.DataType getConcreteValueDataType(String rangeConstraint) {
        int length = rangeConstraint.length();
        if (length >= 3) {
            rangeConstraint = rangeConstraint.substring(0, 3);
            return ConcreteValue.DataType.fromShorthand(rangeConstraint);
        }

        return null;
    }

}
