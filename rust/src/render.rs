use crate::model::{CropFillMode, PageItem, WallpaperAppState};
use jni::objects::{JObject, JValue};
use jni::JNIEnv;

const BACKGROUND_COLOR: i32 = 0xff141922u32 as i32;

pub fn draw(
    env: &mut JNIEnv,
    canvas: &JObject,
    image_a: &JObject,
    blur_a: &JObject,
    image_b: &JObject,
    blur_b: &JObject,
    state: &WallpaperAppState,
    page_a: usize,
    page_b: usize,
    progress: f32,
    forward: bool,
    width: i32,
    height: i32,
    dim_active: bool,
    motion_blur_radius: i32,
    motion_blur_direction: i32,
) -> jni::errors::Result<()> {
    call_void(
        env,
        canvas,
        "drawColor",
        "(I)V",
        &[JValue::Int(BACKGROUND_COLOR)],
    )?;
    if width <= 0 || height <= 0 {
        return Ok(());
    }

    let a = state.pages.get(page_a);
    let b = state.pages.get(page_b);
    let setting = state.transitions.get(page_a);
    let transition = setting
        .map(|value| value.transition_id(forward))
        .unwrap_or(&state.transition_type);
    let p = if progress.is_finite() {
        progress.clamp(0.0, 1.0)
    } else {
        0.0
    };
    match transition {
        "fade" | "soft_blur" => {
            if let Some(page) = a {
                draw_page(
                    env,
                    canvas,
                    image_a,
                    blur_a,
                    page,
                    0.0,
                    p,
                    p,
                    255 - (p * 255.0) as i32,
                    width,
                    height,
                )?;
            }
            if let Some(page) = b {
                draw_page(
                    env,
                    canvas,
                    image_b,
                    blur_b,
                    page,
                    0.0,
                    1.0 - p,
                    1.0 - p,
                    (p * 255.0) as i32,
                    width,
                    height,
                )?;
            }
            if transition == "soft_blur" {
                let blur_alpha = ((std::f32::consts::PI * p).sin().max(0.0) * 55.0) as i32;
                draw_soft_blur(env, canvas, blur_a, blur_b, p, blur_alpha, width, height)?;
            }
        }
        "depth" => {
            if let Some(page) = a {
                draw_page_scaled(
                    env,
                    canvas,
                    image_a,
                    blur_a,
                    page,
                    1.0 - 0.22 * p,
                    255 - (p * 255.0) as i32,
                    width,
                    height,
                )?;
            }
            if let Some(page) = b {
                draw_page_scaled(
                    env,
                    canvas,
                    image_b,
                    blur_b,
                    page,
                    1.15 - 0.15 * p,
                    (p * 255.0) as i32,
                    width,
                    height,
                )?;
            }
        }
        "slide_over" => {
            if let Some(page) = a {
                draw_page(
                    env,
                    canvas,
                    image_a,
                    blur_a,
                    page,
                    0.0,
                    p,
                    p,
                    255,
                    width,
                    height,
                )?;
            }
            draw_rect(
                env,
                canvas,
                0.0,
                0.0,
                width as f32,
                height as f32,
                0xff000000u32 as i32,
                (p * 110.0) as i32,
            )?;
            if let Some(page) = b {
                draw_page(
                    env,
                    canvas,
                    image_b,
                    blur_b,
                    page,
                    (1.0 - p) * width as f32,
                    1.0 - p,
                    1.0 - p,
                    255,
                    width,
                    height,
                )?;
                let edge = (width as f32 * 0.05).max(1.0);
                draw_rect(
                    env,
                    canvas,
                    ((1.0 - p) * width as f32 - edge).max(0.0),
                    0.0,
                    (1.0 - p) * width as f32,
                    height as f32,
                    0xff000000u32 as i32,
                    120,
                )?;
            }
        }
        "cube" => {
            if p < 1.0 {
                if let Some(page) = a {
                    draw_cube_page(
                        env,
                        canvas,
                        image_a,
                        blur_a,
                        page,
                        -90.0 * p * if forward { 1.0 } else { -1.0 },
                        true,
                        width,
                        height,
                    )?;
                }
            }
            if p > 0.0 && page_a != page_b {
                if let Some(page) = b {
                    draw_cube_page(
                        env,
                        canvas,
                        image_b,
                        blur_b,
                        page,
                        90.0 * (1.0 - p) * if forward { 1.0 } else { -1.0 },
                        false,
                        width,
                        height,
                    )?;
                }
            }
        }
        "parallax" | "parallax_classic" => {
            let speed = setting
                .map(|transition| transition.parallax_motion(forward))
                .filter(|motion| motion.slow_image_motion_enabled)
                .map(|motion| motion.image_speed)
                .unwrap_or(1.0);
            let classic = transition == "parallax_classic";
            if let Some(page) = a {
                let source_progress = if classic {
                    p
                } else {
                    p * (1.0 - speed)
                };
                draw_page(
                    env,
                    canvas,
                    image_a,
                    blur_a,
                    page,
                    -p * width as f32,
                    source_progress,
                    p,
                    255,
                    width,
                    height,
                )?;
            }
            if let Some(page) = b {
                let source_progress = if classic {
                    1.0 - p
                } else {
                    (1.0 - p) * (1.0 - speed)
                };
                draw_page(
                    env,
                    canvas,
                    image_b,
                    blur_b,
                    page,
                    (1.0 - p) * width as f32,
                    source_progress,
                    1.0 - p,
                    255,
                    width,
                    height,
                )?;
            }
        }
        _ => {
            if let Some(page) = a {
                draw_page(
                    env,
                    canvas,
                    image_a,
                    blur_a,
                    page,
                    -p * width as f32,
                    p,
                    p,
                    255,
                    width,
                    height,
                )?;
            }
            if let Some(page) = b {
                draw_page(
                    env,
                    canvas,
                    image_b,
                    blur_b,
                    page,
                    (1.0 - p) * width as f32,
                    1.0 - p,
                    1.0 - p,
                    255,
                    width,
                    height,
                )?;
            }
        }
    }

    if state.motion_blur_enabled
        && motion_blur_radius > 0
        && motion_blur_direction != 0
        && matches!(
            transition,
            "scroll" | "parallax" | "parallax_classic" | "slide_over"
        )
    {
        let ghost_alpha = ((motion_blur_radius as f32 / width as f32) * 170.0)
            .round()
            .clamp(0.0, 64.0) as i32;
        if ghost_alpha > 0 {
            for side in [-1.0_f32, 1.0] {
                let displacement = side * motion_blur_radius as f32 * 0.45
                    * motion_blur_direction as f32;
                if let Some(page) = a {
                    draw_page(
                        env,
                        canvas,
                        image_a,
                        blur_a,
                        page,
                        -p * width as f32 + displacement,
                        p,
                        p,
                        ghost_alpha,
                        width,
                        height,
                    )?;
                }
                if let Some(page) = b {
                    draw_page(
                        env,
                        canvas,
                        image_b,
                        blur_b,
                        page,
                        (1.0 - p) * width as f32 + displacement,
                        1.0 - p,
                        1.0 - p,
                        ghost_alpha,
                        width,
                        height,
                    )?;
                }
            }
        }
    }

    if dim_active {
        let is_a_dark = state.darken.auto_detect_dark
            && a.map(|page| page.is_already_dark).unwrap_or(false);
        let is_b_dark = state.darken.auto_detect_dark
            && b.map(|page| page.is_already_dark).unwrap_or(false);
        let dim_factor = match (is_a_dark, is_b_dark) {
            (true, true) => 0.0,
            (true, false) => state.darken.dim_factor * p,
            (false, true) => state.darken.dim_factor * (1.0 - p),
            (false, false) => state.darken.dim_factor,
        };
        if dim_factor > 0.01 {
            let paint = new_paint(env, (dim_factor.clamp(0.0, 1.0) * 255.0) as i32)?;
            call_void(
                env,
                canvas,
                "drawRect",
                "(FFFFLandroid/graphics/Paint;)V",
                &[
                    JValue::Float(0.0),
                    JValue::Float(0.0),
                    JValue::Float(width as f32),
                    JValue::Float(height as f32),
                    JValue::Object(&paint),
                ],
            )?;
        }
    }
    Ok(())
}

fn draw_page(
    env: &mut JNIEnv,
    canvas: &JObject,
    bitmap: &JObject,
    blurred: &JObject,
    page: &PageItem,
    destination_left: f32,
    parallax_progress: f32,
    motion_progress: f32,
    alpha: i32,
    width: i32,
    height: i32,
) -> jni::errors::Result<()> {
    if bitmap.is_null() {
        if page.is_blank {
            return Ok(());
        }
        draw_starter(env, canvas, page.page_index, destination_left, width, height, alpha)?;
        return Ok(());
    }
    if !matches!(page.crop_fill_mode, CropFillMode::Crop) {
        if !blurred.is_null() {
            let alpha_paint = new_paint(env, alpha)?;
            draw_bitmap(
                env,
                canvas,
                blurred,
                None,
                destination_left,
                0.0,
                destination_left + width as f32,
                height as f32,
                &alpha_paint,
            )?;
        } else {
            let background = new_paint(env, (alpha * 160 / 255).clamp(0, 255))?;
            draw_bitmap(
                env,
                canvas,
                bitmap,
                None,
                destination_left,
                0.0,
                destination_left + width as f32,
                height as f32,
                &background,
            )?;
        }
        if matches!(page.crop_fill_mode, CropFillMode::Glass) {
            draw_rect(
                env,
                canvas,
                destination_left,
                0.0,
                destination_left + width as f32,
                height as f32,
                0xff181c26u32 as i32,
                (alpha * 58 / 255).clamp(0, 255),
            )?;
        }
    }
    let bitmap_width = env.call_method(bitmap, "getWidth", "()I", &[])?.i()?;
    let bitmap_height = env.call_method(bitmap, "getHeight", "()I", &[])?.i()?;
    if bitmap_width <= 0 || bitmap_height <= 0 {
        return Ok(());
    }
    let source_width = bitmap_width as f32;
    let source_height = bitmap_height as f32;
    let target_ratio = width as f32 / height as f32;
    let source_ratio = source_width / source_height;
    let scale = page.crop_scale.clamp(1.0, 3.0);
    let pan_x = page.crop_pan_x.clamp(-1.0, 1.0);
    let pan_y = page.crop_pan_y.clamp(-1.0, 1.0);
    let paint = new_paint(env, alpha)?;

    if matches!(page.crop_fill_mode, CropFillMode::Crop) {
        let base_width = if source_ratio > target_ratio {
            source_height * target_ratio
        } else {
            source_width
        };
        let base_height = if source_ratio > target_ratio {
            source_height
        } else {
            source_width / target_ratio
        };
        let crop_width = (base_width / scale).clamp(1.0, source_width);
        let crop_height = (base_height / scale).clamp(1.0, source_height);
        let center_x = source_width / 2.0
            - pan_x * ((source_width - crop_width) / 2.0).max(0.0);
        let center_y = source_height / 2.0
            - pan_y * ((source_height - crop_height) / 2.0).max(0.0);
        let max_left = (source_width - crop_width).max(0.0);
        let max_top = (source_height - crop_height).max(0.0);
        let available = (max_left * page.parallax_amount.clamp(0.0, 1.0)).max(0.0);
        let directional = if motion_progress >= 0.5 {
            parallax_progress
        } else {
            1.0 - parallax_progress
        };
        let left = (center_x - crop_width / 2.0 + available * directional)
            .clamp(0.0, max_left);
        let top = (center_y - crop_height / 2.0).clamp(0.0, max_top);
        draw_bitmap(
            env,
            canvas,
            bitmap,
            Some((
                left as i32,
                top as i32,
                (left + crop_width).ceil() as i32,
                (top + crop_height).ceil() as i32,
            )),
            destination_left,
            0.0,
            destination_left + width as f32,
            height as f32,
            &paint,
        )?;
    } else {
        let fit_scale = (width as f32 / source_width)
            .min(height as f32 / source_height)
            * scale;
        let draw_width = source_width * fit_scale;
        let draw_height = source_height * fit_scale;
        let pan_x_px = pan_x * ((draw_width - width as f32) / 2.0).max(0.0);
        let pan_y_px = pan_y * ((draw_height - height as f32) / 2.0).max(0.0);
        let parallax_px = page.parallax_amount.clamp(0.0, 1.0)
            * ((draw_width - width as f32).max(0.0) / 2.0);
        let directional = if motion_progress >= 0.5 {
            parallax_progress
        } else {
            1.0 - parallax_progress
        };
        let left = destination_left + (width as f32 - draw_width) / 2.0
            + pan_x_px
            + parallax_px * directional;
        let top = (height as f32 - draw_height) / 2.0 + pan_y_px;
        draw_bitmap(
            env,
            canvas,
            bitmap,
            None,
            left,
            top,
            left + draw_width,
            top + draw_height,
            &paint,
        )?;
    }
    Ok(())
}

fn draw_page_scaled(
    env: &mut JNIEnv,
    canvas: &JObject,
    bitmap: &JObject,
    blurred: &JObject,
    page: &PageItem,
    scale: f32,
    alpha: i32,
    width: i32,
    height: i32,
) -> jni::errors::Result<()> {
    let saved = env.call_method(canvas, "save", "()I", &[])?.i()?;
    call_void(
        env,
        canvas,
        "scale",
        "(FFFF)V",
        &[
            JValue::Float(scale),
            JValue::Float(scale),
            JValue::Float(width as f32 / 2.0),
            JValue::Float(height as f32 / 2.0),
        ],
    )?;
    let result = draw_page(
        env, canvas, bitmap, blurred, page, 0.0, 0.5, 0.5, alpha, width, height,
    );
    call_void(
        env,
        canvas,
        "restoreToCount",
        "(I)V",
        &[JValue::Int(saved)],
    )?;
    result
}

fn draw_cube_page(
    env: &mut JNIEnv,
    canvas: &JObject,
    bitmap: &JObject,
    blurred: &JObject,
    page: &PageItem,
    rotation: f32,
    leaving: bool,
    width: i32,
    height: i32,
) -> jni::errors::Result<()> {
    let saved = env.call_method(canvas, "save", "()I", &[])?.i()?;
    let camera = env.new_object("android/graphics/Camera", "()V", &[])?;
    let matrix = env.new_object("android/graphics/Matrix", "()V", &[])?;
    env.call_method(&camera, "save", "()V", &[])?;
    env.call_method(&camera, "rotateY", "(F)V", &[JValue::Float(rotation)])?;
    env.call_method(&matrix, "reset", "()V", &[])?;
    env.call_method(
        &camera,
        "getMatrix",
        "(Landroid/graphics/Matrix;)V",
        &[JValue::Object(&matrix)],
    )?;
    env.call_method(&camera, "restore", "()V", &[])?;
    let translate_x = if leaving { -(width as f32) } else { 0.0 };
    env.call_method(
        &matrix,
        "preTranslate",
        "(FF)Z",
        &[
            JValue::Float(translate_x),
            JValue::Float(-(height as f32) / 2.0),
        ],
    )?;
    env.call_method(
        &matrix,
        "postTranslate",
        "(FF)Z",
        &[
            JValue::Float(if leaving { width as f32 } else { 0.0 }),
            JValue::Float(height as f32 / 2.0),
        ],
    )?;
    call_void(
        env,
        canvas,
        "concat",
        "(Landroid/graphics/Matrix;)V",
        &[JValue::Object(&matrix)],
    )?;
    let result = draw_page(
        env, canvas, bitmap, blurred, page, 0.0, 0.5, 0.5, 255, width, height,
    )
    .and_then(|_| {
        draw_rect(
            env,
            canvas,
            0.0,
            0.0,
            width as f32,
            height as f32,
            0xff000000u32 as i32,
            (rotation.abs() / 90.0 * 180.0) as i32,
        )
    });
    call_void(
        env,
        canvas,
        "restoreToCount",
        "(I)V",
        &[JValue::Int(saved)],
    )?;
    result
}

fn draw_soft_blur(
    env: &mut JNIEnv,
    canvas: &JObject,
    blur_a: &JObject,
    blur_b: &JObject,
    progress: f32,
    alpha: i32,
    width: i32,
    height: i32,
) -> jni::errors::Result<()> {
    if alpha == 0 {
        return Ok(());
    }
    let p = progress * progress * (3.0 - 2.0 * progress);
    for (bitmap, item_alpha) in [(blur_a, (1.0 - p) * alpha as f32), (blur_b, p * alpha as f32)] {
        if bitmap.is_null() {
            continue;
        }
        let paint = new_paint(env, item_alpha as i32)?;
        draw_bitmap(
            env,
            canvas,
            bitmap,
            None,
            0.0,
            0.0,
            width as f32,
            height as f32,
            &paint,
        )?;
    }
    Ok(())
}

fn draw_starter(
    env: &mut JNIEnv,
    canvas: &JObject,
    page_index: usize,
    left: f32,
    width: i32,
    height: i32,
    alpha: i32,
) -> jni::errors::Result<()> {
    let colors = [
        0xff0e7490u32 as i32,
        0xff9333eau32 as i32,
        0xff065f46u32 as i32,
        0xff1d4ed8u32 as i32,
        0xffbe123cu32 as i32,
        0xff7c2d12u32 as i32,
        0xff334155u32 as i32,
    ];
    let paint = new_paint(env, alpha)?;
    let shader = env.new_object(
        "android/graphics/LinearGradient",
        "(FFFFIILandroid/graphics/Shader$TileMode;)V",
        &[
            JValue::Float(left),
            JValue::Float(0.0),
            JValue::Float(left + width as f32),
            JValue::Float(height as f32),
            JValue::Int(0xff0f172au32 as i32),
            JValue::Int(colors[page_index % colors.len()]),
            JValue::Object(&env.get_static_field(
                "android/graphics/Shader$TileMode",
                "CLAMP",
                "Landroid/graphics/Shader$TileMode;",
            )?.l()?),
        ],
    )?;
    env.call_method(
        &paint,
        "setShader",
        "(Landroid/graphics/Shader;)Landroid/graphics/Shader;",
        &[JValue::Object(&shader)],
    )?;
    call_void(
        env,
        canvas,
        "drawRect",
        "(FFFFLandroid/graphics/Paint;)V",
        &[
            JValue::Float(left),
            JValue::Float(0.0),
            JValue::Float(left + width as f32),
            JValue::Float(height as f32),
            JValue::Object(&paint),
        ],
    )
}

fn draw_rect(
    env: &mut JNIEnv,
    canvas: &JObject,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    color: i32,
    alpha: i32,
) -> jni::errors::Result<()> {
    if alpha <= 0 || right <= left || bottom <= top {
        return Ok(());
    }
    let paint = new_paint(env, alpha)?;
    env.call_method(
        &paint,
        "setColor",
        "(I)V",
        &[JValue::Int(color)],
    )?;
    env.call_method(
        &paint,
        "setAlpha",
        "(I)V",
        &[JValue::Int(alpha.clamp(0, 255))],
    )?;
    call_void(
        env,
        canvas,
        "drawRect",
        "(FFFFLandroid/graphics/Paint;)V",
        &[
            JValue::Float(left),
            JValue::Float(top),
            JValue::Float(right),
            JValue::Float(bottom),
            JValue::Object(&paint),
        ],
    )
}

fn new_paint<'local>(
    env: &mut JNIEnv<'local>,
    alpha: i32,
) -> jni::errors::Result<JObject<'local>> {
    let paint = env.new_object("android/graphics/Paint", "(I)V", &[JValue::Int(3)])?;
    env.call_method(
        &paint,
        "setAlpha",
        "(I)V",
        &[JValue::Int(alpha.clamp(0, 255))],
    )?;
    Ok(paint)
}

fn draw_bitmap(
    env: &mut JNIEnv,
    canvas: &JObject,
    bitmap: &JObject,
    source: Option<(i32, i32, i32, i32)>,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    paint: &JObject,
) -> jni::errors::Result<()> {
    let source = if let Some((source_left, source_top, source_right, source_bottom)) = source {
        let rectangle = env.new_object("android/graphics/Rect", "()V", &[])?;
        call_void(
            env,
            &rectangle,
            "set",
            "(IIII)V",
            &[
                JValue::Int(source_left),
                JValue::Int(source_top),
                JValue::Int(source_right),
                JValue::Int(source_bottom),
            ],
        )?;
        rectangle
    } else {
        JObject::null()
    };
    let destination = env.new_object("android/graphics/RectF", "()V", &[])?;
    call_void(
        env,
        &destination,
        "set",
        "(FFFF)V",
        &[
            JValue::Float(left),
            JValue::Float(top),
            JValue::Float(right),
            JValue::Float(bottom),
        ],
    )?;
    call_void(
        env,
        canvas,
        "drawBitmap",
        "(Landroid/graphics/Bitmap;Landroid/graphics/Rect;Landroid/graphics/RectF;Landroid/graphics/Paint;)V",
        &[
            JValue::Object(bitmap),
            JValue::Object(&source),
            JValue::Object(&destination),
            JValue::Object(paint),
        ],
    )
}

fn call_void(
    env: &mut JNIEnv,
    object: &JObject,
    name: &str,
    signature: &str,
    args: &[JValue],
) -> jni::errors::Result<()> {
    env.call_method(object, name, signature, args).map(|_| ())
}
