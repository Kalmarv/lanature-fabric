#!/usr/bin/env bash
# Clone the reference data the converter reads into refs/ at pinned commits.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p refs

fetch() { # name url commit [sparse paths...]
	local name=$1 url=$2 commit=$3; shift 3
	local dir=refs/$name
	if [ ! -d "$dir/.git" ]; then
		git clone -q --filter=blob:none --no-checkout "$url" "$dir"
	fi
	if [ $# -gt 0 ]; then
		git -C "$dir" sparse-checkout set "$@"
	fi
	git -C "$dir" fetch -q --depth 1 origin "$commit"
	git -C "$dir" checkout -q "$commit"
	echo "$name $(git -C "$dir" rev-parse --short HEAD)"
}

fetch bedrock-samples https://github.com/Mojang/bedrock-samples.git 15701fa9c13923f12662984c32c124327e40e3d3 resource_pack behavior_pack/biomes behavior_pack/entities
# the last samples release whose entity geometry still carries bind_pose_rotation (v1.21.0.3); see BindPoses
fetch bedrock-samples-1.21.0.3 https://github.com/Mojang/bedrock-samples.git c989e80c35508d658eb4a11932a04382feee4217 resource_pack/models
# the last vanilla entity.material shipped in a resource pack (1.16.100 beta), in bridge.'s copy of the vanilla pack
fetch bridge-vanilla https://github.com/bridge-core/bridge..git e3f00f6f649263383c96d578b9409dcb0fbf25cd static/vanilla/RP/materials
fetch geyser-mappings https://github.com/GeyserMC/mappings.git b4cec82d49fe75f222e1ad6dc0fd2a23ee89626c
fetch pack-converter https://github.com/GeyserMC/PackConverter.git 67014a128a94aa36faae56de396aedacded93a23 converter/src/main/resources/mappings
