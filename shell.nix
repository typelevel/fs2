{ java ? "openjdk11" }:

let
  jdk = pkgs.${java};

  config = {
    packageOverrides = p: rec {
      sbt = p.sbt.overrideAttrs (
        old: rec {
          version = "1.4.6";

          src = builtins.fetchurl {
            url    = "https://github.com/sbt/sbt/releases/download/v${version}/sbt-${version}.tgz";
            sha256 = "194xdz55cq4w7jlxl8df9vacil37jahimav620878q4ng67g59l6";
          };

          patchPhase = ''
            echo -java-home ${jdk} >> conf/sbtopts
          '';
        }
      );
    };
  };

  nixpkgs = builtins.fetchTarball {
    name   = "nixos-unstable-2021-01-03";
    url    = "https://github.com/NixOS/nixpkgs/archive/56bb1b0f7a3.tar.gz";
    sha256 = "1wl5yglgj3ajbf2j4dzgsxmgz7iqydfs514w73fs9a6x253wzjbs";
  };

  pkgs = import nixpkgs { inherit config; };
in
  pkgs.mkShell {
    buildInputs = [
      jdk
      pkgs.nodejs-14_x
      pkgs.sbt
    ];

    shellHook = ''
      npm i docsify-cli
    '';
  }
