mod model;
mod render;
mod store;

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jfloat, jstring};
use jni::JNIEnv;
use std::path::PathBuf;

#[no_mangle]
pub extern "system" fn Java_dev_pagedwallpaper_rust_NativeCore_initialize(
    mut env: JNIEnv,
    _core: JObject,
    directory: JString,
) -> jstring {
    let result = (|| {
        let directory: String = env
            .get_string(&directory)
            .map_err(|error| error.to_string())?
            .into();
        store::initialize(PathBuf::from(directory))
            .map_err(|error| error.to_string())?;
        snapshot()
    })();
    return_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_pagedwallpaper_rust_NativeCore_state(
    mut env: JNIEnv,
    _core: JObject,
) -> jstring {
    return_result(&mut env, snapshot())
}

#[no_mangle]
pub extern "system" fn Java_dev_pagedwallpaper_rust_NativeCore_reload(
    mut env: JNIEnv,
    _core: JObject,
) -> jstring {
    let result = (|| {
        store::lock_store()?
            .reload_from_disk()
            .map_err(|error| error.to_string())?;
        snapshot()
    })();
    return_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_pagedwallpaper_rust_NativeCore_validateBackup(
    mut env: JNIEnv,
    _core: JObject,
    directory: JString,
) {
    let result = (|| {
        let directory: String = env
            .get_string(&directory)
            .map_err(|error| error.to_string())?
            .into();
        store::validate_backup(PathBuf::from(directory)).map_err(|error| error.to_string())
    })();
    if let Err(error) = result {
        throw_error(&mut env, "java/lang/IllegalArgumentException", &error);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_pagedwallpaper_rust_NativeCore_apply(
    mut env: JNIEnv,
    _core: JObject,
    command: JString,
) -> jstring {
    let result = (|| {
        let command: String = env
            .get_string(&command)
            .map_err(|error| error.to_string())?
            .into();
        let command = serde_json::from_str(&command).map_err(|error| error.to_string())?;
        let mut store = store::lock_store()?;
        store.apply_command(command)?;
        store.snapshot().map_err(|error| error.to_string())
    })();
    return_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_pagedwallpaper_rust_NativeCore_setLastOffset(
    mut env: JNIEnv,
    _core: JObject,
    offset: jfloat,
) {
    if let Err(error) = store::lock_store()
        .and_then(|mut store| store.cache_last_offset(offset).map_err(|error| error.to_string()))
    {
        throw_error(&mut env, "java/lang/IllegalArgumentException", &error);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_pagedwallpaper_rust_NativeCore_flushLastOffset(
    mut env: JNIEnv,
    _core: JObject,
) {
    if let Err(error) = store::lock_store()
        .and_then(|store| store.persist_last_offset().map_err(|error| error.to_string()))
    {
        throw_error(&mut env, "java/lang/IllegalStateException", &error);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_pagedwallpaper_rust_NativeRenderer_draw(
    mut env: JNIEnv,
    _renderer: JObject,
    canvas: JObject,
    image_a: JObject,
    blur_a: JObject,
    image_b: JObject,
    blur_b: JObject,
    page_a: jint,
    page_b: jint,
    progress: jfloat,
    forward: jboolean,
    width: jint,
    height: jint,
    dim_active: jboolean,
    motion_blur_radius: jint,
    motion_blur_direction: jint,
) {
    let result = (|| {
        let store = store::lock_store()?;
        render::draw(
            &mut env,
            &canvas,
            &image_a,
            &blur_a,
            &image_b,
            &blur_b,
            store.state(),
            page_a.max(0) as usize,
            page_b.max(0) as usize,
            progress,
            forward != 0,
            width,
            height,
            dim_active != 0,
            motion_blur_radius,
            motion_blur_direction,
        )
        .map_err(|error| error.to_string())
    })();
    if let Err(error) = result {
        throw_error(&mut env, "java/lang/IllegalStateException", &error);
    }
}

fn snapshot() -> Result<String, String> {
    store::lock_store()?
        .snapshot()
        .map_err(|error| error.to_string())
}

fn return_result(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    match result {
        Ok(value) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(error) => {
                throw_error(env, "java/lang/IllegalStateException", &error.to_string());
                std::ptr::null_mut()
            }
        },
        Err(error) => {
            throw_error(env, "java/lang/IllegalStateException", &error);
            std::ptr::null_mut()
        }
    }
}

fn throw_error(env: &mut JNIEnv, class: &str, message: &str) {
    if let Err(error) = env.throw_new(class, message) {
        eprintln!("Could not raise Java exception: {error}; original error: {message}");
    }
}
