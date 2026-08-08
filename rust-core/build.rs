fn main() {
    println!("cargo:rerun-if-changed=src/lib.udl");
    uniffi::generate_scaffolding("src/lib.udl").expect("Ошибка генерации UniFFI scaffolding");
}
