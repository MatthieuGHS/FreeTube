"""`python -m freetube.hash_password`: prints the argon2 hash for FREETUBE_PASSWORD_HASH."""

from getpass import getpass

from argon2 import PasswordHasher


def main() -> None:
    password = getpass("Mot de passe : ")
    if not password or password != getpass("Confirmation : "):
        raise SystemExit("Les mots de passe sont vides ou différents.")
    # Single quotes: docker compose interpolates `$` in env files, argon2 hashes are full of it.
    print(f"FREETUBE_PASSWORD_HASH='{PasswordHasher().hash(password)}'")


if __name__ == "__main__":
    main()
