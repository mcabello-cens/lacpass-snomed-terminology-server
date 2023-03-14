package org.snomed.snowstorm.core.data.domain.review;

import io.kaicode.elasticvc.domain.Branch;

public class BranchState {

	private String path;
	private Long baseTimestamp;
	private Long headTimestamp;

	public BranchState() {
	}

	public BranchState(Branch branch) {
		path = branch.getPath();
		baseTimestamp = branch.getBase().getTime();
		headTimestamp = branch.getHead().getTime();
	}

	public String getPath() {
		return path;
	}

	public Long getBaseTimestamp() {
		return baseTimestamp;
	}

	public Long getHeadTimestamp() {
		return headTimestamp;
	}
}
