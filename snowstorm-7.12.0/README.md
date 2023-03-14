![Snowstorm Terminology Server](docs/images/SNOWSTORM-logo.svg)

[![Build Status](https://travis-ci.org/IHTSDO/snowstorm.svg?branch=master)](https://travis-ci.org/IHTSDO/snowstorm) [![Language grade: Java](https://img.shields.io/lgtm/grade/java/g/IHTSDO/snowstorm.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/IHTSDO/snowstorm/context:java) [![codecov](https://codecov.io/gh/IHTSDO/snowstorm/branch/master/graph/badge.svg)](https://codecov.io/gh/IHTSDO/snowstorm)
[![Docker](https://img.shields.io/docker/pulls/snomedinternational/snowstorm)](https://hub.docker.com/r/snomedinternational/snowstorm)

Snowstorm is a SNOMED CT terminology server built on top of Elasticsearch, with a focus on performance and enterprise scalability.

## Project Status

### Read-Only Use
Snowstorm provides the terminology server API for the SNOMED International Browser including the International Edition and around fourteen national Extensions.

Snowstorm can be used in local implementations to query SNOMED CT with the following features:
- Hosting multiple extensions alongside the International Edition of SNOMED CT
- Multi-lingual search and content retrieval
- Fully ECL v2.0 compliant
- Full history (depends on full RF2 import)
- Read-only FHIR API :fire:

### Authoring Use
Snowstorm also provides the terminology server API for the SNOMED International Authoring Platform.

The Authoring Platform is used for the maintenance of the International Edition of SNOMED CT as well as nine national Extensions and several community content Extensions.

## Documentation

- Setup
  - [Getting Started (plain installation)](docs/getting-started.md)
  - [Configuration Guide](docs/configuration-guide.md)
    - [Security Configuration Guide](docs/security-configuration.md)
  - [Loading SNOMED](docs/loading-snomed.md)
  - [Loading & updating SNOMED CT with local Extensions or Editions](docs/updating-snomed-and-extensions.md)
  - [Extension Authoring](docs/extension-authoring.md)
  - [Elasticsearch Index Mapping Changes](docs/index-mapping-changes.md)
  - [Docker Quickstart](docs/using-docker.md)
  - [Load Balancing](docs/load-balancing.md)
- Use
  - [Using the API](docs/using-the-api.md)
    - [Code Systems & Branches](docs/code-systems-and-branches.md)
    - [Search Guide](docs/search.md)
    - [Language Specific Search Behaviour](docs/language-specific-search.md)
  - [Using the FHIR API](docs/using-the-fhir-api.md)

## Contributing :star:

We welcome questions, ideas, issues and code contributions to this project. 

Use the [issues page](https://github.com/IHTSDO/snowstorm/issues) to get in touch with the community. 

If you would like to make a code contribution please fork the repository and create a 
[GitHub pull request](https://help.github.com/en/github/collaborating-with-issues-and-pull-requests) to the `develop` branch.

## License

Apache 2.0

See the included LICENSE file for details.

## Tools

For Java performance profiling we recommend the JProfiler [Java profiler](https://www.ej-technologies.com/products/jprofiler/overview.html).
