# dev/elasticsearch/Dockerfile — Story 0.3
# Installs the Vietnamese analyzer plugin per FR-52.
# Falls back to analysis-stconvert if analysis-vn is not available
# for this ES version (see Story 6.2 for application-layer fallback).

FROM docker.elastic.co/elasticsearch/elasticsearch:8.15.0

# analysis-vn is the canonical Vietnamese analyzer (FR-52). If it fails to
# install (not published for this ES minor), the build fails loudly here so
# the operator notices and swaps to analysis-stconvert (last-resort fallback).
RUN bin/elasticsearch-plugin install --batch analysis-vn \
 || (echo "WARN: analysis-vn install failed, falling back to analysis-stconvert" \
     && bin/elasticsearch-plugin install --batch analysis-stconvert)
