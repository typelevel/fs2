{ java ? "openjdk16" }:

let
  jdk = pkgs.${java};

  config = {
    packageOverrides = p: rec {
      sbt = p.sbt.overrideAttrs (
        old: rec {
          patchPhase = ''
            echo -java-home ${jdk} >> conf/sbtopts
          '';
        }
      );
    };
  };

  nixpkgs = builtins.fetchTarball {
    name   = "nixos-21.05";
    url    = "https://github.com/NixOS/nixpkgs/archive/refs/tags/21.05.tar.gz";
    sha256 = "1ckzhh24mgz6jd1xhfgx0i9mijk6xjqxwsshnvq789xsavrmsc36";
  };

  pkgs = import nixpkgs { inherit config; };
in
  pkgs.mkShell {
    buildInputs = [
      jdk
      pkgs.nodejs-14_x
      pkgs.yarn
      pkgs.sbt
    ];

    shellHook = ''
      npm i docsify-cli
    '';
  }
