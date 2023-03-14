package org.snomed.snowstorm.core.data.domain.security;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.collect.Sets;
import org.snomed.snowstorm.rest.View;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.Set;

@Document(indexName = "admin-permission")
@JsonPropertyOrder({"role", "path", "global", "userGroups"})
public class PermissionRecord {

	public interface Fields {
		String ROLE = "role";
		String GLOBAL = "global";
		String PATH = "path";
		String USER_GROUPS = "userGroups";
	}

	@Id
	@Field(type = FieldType.Keyword)
	private String key;

	@JsonView(value = View.Component.class)
	@Field(type = FieldType.Boolean)
	private boolean global;

	@JsonView(value = View.Component.class)
	@Field(type = FieldType.Keyword)
	private String role;

	@JsonView(value = View.Component.class)
	@Field(type = FieldType.Keyword)
	private String path;

	// These may or may not be of type Role.
	@JsonView(value = View.Component.class)
	@Field(type = FieldType.Keyword)
	private Set<String> userGroups;

	// For Jackson
	private PermissionRecord() {
	}

	public PermissionRecord(String role) {
		this.role = role;
	}

	public PermissionRecord(String role, String path) {
		this.role = role;
		this.path = path;
	}

	public void updateFields() {
		// Composite key enforces uniqueness, not used for lookup, not API visible.
		key = (path != null ? path : "global") + "-" + role;
		global = path == null;
	}

	public boolean isGlobal() {
		return global;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public Set<String> getUserGroups() {
		return userGroups;
	}

	public void setUserGroups(Set<String> userGroups) {
		this.userGroups = userGroups;
	}

	public PermissionRecord withUserGroups(String... userGroups) {
		this.userGroups = Sets.newHashSet(userGroups);
		return this;
	}

	@Override
	public String toString() {
		return "PermissionRecord{" +
				"key='" + key + '\'' +
				", global=" + global +
				", role='" + role + '\'' +
				", path='" + path + '\'' +
				", userGroups=" + userGroups +
				'}';
	}
}
