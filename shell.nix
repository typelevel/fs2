{ java ? "openjdk11" }:

let
  pkgs = import nix/pkgs.nix { inherit java; };
in
  pkgs.mkShell {
    buildInputs = [
      pkgs.jekyll
      pkgs.${java}
      pkgs.sbt
    ];
  }
