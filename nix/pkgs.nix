{ java }:

let
  nixpkgs = fetchTarball {
    name   = "nixos-unstable-2020-09-25";
    url    = "https://github.com/NixOS/nixpkgs-channels/archive/72b9660dc18b.tar.gz";
    sha256 = "1cqgpw263bz261bgz34j6hiawi4hi6smwp6981yz375fx0g6kmss";
  };

  config = {
    packageOverrides = p: {
      sbt = p.sbt.override {
        jre = p.${java};
      };
    };
  };

  pkgs = import nixpkgs { inherit config; };
in
  pkgs
