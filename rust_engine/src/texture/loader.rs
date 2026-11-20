//! 纹理加载

use image::{DynamicImage, GenericImageView, ImageDecoder, ImageReader};
use std::path::Path;

use super::Texture;
use crate::{MmdError, Result};

/// 从文件加载纹理
pub fn load_texture<P: AsRef<Path>>(path: P) -> Result<Texture> {
    let path = path.as_ref();
    let reader = ImageReader::open(path)
        .and_then(|reader| reader.with_guessed_format())
        .map_err(|error| texture_error(path, error))?;
    let img = reader
        .decode()
        .map_err(|error| texture_error(path, error))?;

    Ok(texture_from_image(img))
}

pub(crate) fn load_texture_limited(path: &Path, pixel_limit: u32) -> Result<Texture> {
    const MAX_INPUT: u64 = 64 * 1024 * 1024;
    const MAX_DECODE: u64 = 96 * 1024 * 1024;
    let meta = std::fs::metadata(path).map_err(|error| texture_error(path, error))?;
    if !meta.is_file() || meta.len() > MAX_INPUT {
        return Err(texture_error(path, "effect texture must be a regular file of at most 64 MiB"));
    }
    let mut reader = ImageReader::open(path)
        .and_then(|reader| reader.with_guessed_format())
        .map_err(|error| texture_error(path, error))?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(4096);
    limits.max_image_height = Some(4096);
    limits.max_alloc = Some(MAX_DECODE);
    reader.limits(limits);
    let decoder = reader.into_decoder().map_err(|error| texture_error(path, error))?;
    let (width, height) = decoder.dimensions();
    if width == 0 || height == 0 || width > 4096 || height > 4096
        || u64::from(width) * u64::from(height) > u64::from(pixel_limit)
        || decoder.total_bytes() > MAX_DECODE
    {
        return Err(texture_error(path, "effect texture exceeds the decoded image budget"));
    }
    DynamicImage::from_decoder(decoder)
        .map(texture_from_image)
        .map_err(|error| texture_error(path, error))
}

fn texture_error(path: &Path, error: impl std::fmt::Display) -> MmdError {
    MmdError::Texture(format!(
        "Failed to load texture path={} error={}",
        bounded_path(path),
        error
    ))
}

fn bounded_path(path: &Path) -> String {
    let value = path.to_string_lossy();
    let mut bounded = String::new();
    let mut truncated = false;
    for (index, character) in value.chars().enumerate() {
        if index == 240 {
            truncated = true;
            break;
        }
        bounded.push(if character.is_control() {
            ' '
        } else {
            character
        });
    }
    if truncated {
        bounded.push_str("...");
    }
    bounded
}

fn texture_from_image(img: DynamicImage) -> Texture {
    let (width, height) = img.dimensions();
    let has_alpha = has_alpha_channel(&img);

    // 与C++一致：垂直翻转图像，然后根据是否有alpha通道选择格式
    let data = if has_alpha {
        // RGBA格式（4字节/像素）
        let mut rgba = img.into_rgba8();
        image::imageops::flip_vertical_in_place(&mut rgba);
        rgba.into_raw()
    } else {
        // RGB格式（3字节/像素）- 与C++的STBI_rgb一致
        let mut rgb = img.into_rgb8();
        image::imageops::flip_vertical_in_place(&mut rgb);
        rgb.into_raw()
    };

    Texture::new(width, height, data, has_alpha)
}

/// 检查图片是否有透明通道
/// 与C++一致：comp == 4 时返回true
fn has_alpha_channel(img: &DynamicImage) -> bool {
    match img {
        DynamicImage::ImageRgba8(_)
        | DynamicImage::ImageRgba16(_)
        | DynamicImage::ImageRgba32F(_)
        | DynamicImage::ImageLumaA8(_)
        | DynamicImage::ImageLumaA16(_) => true,
        _ => false,
    }
}

/// 从内存加载纹理
#[allow(dead_code)]
pub fn load_texture_from_memory(data: &[u8]) -> Result<Texture> {
    let img = image::load_from_memory(data)
        .map_err(|e| MmdError::Texture(format!("Failed to load texture from memory: {}", e)))?;

    Ok(texture_from_image(img))
}

#[cfg(test)]
mod tests {
    use super::{load_texture, load_texture_limited};
    use image::{DynamicImage, ImageFormat, Rgb, Rgba};
    use std::fs;
    use std::io::Cursor;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn load_texture_should_decode_dxt1_dds_file() {
        let bytes = dds_texture("DXT1", 8, &[0x00, 0xF8, 0x00, 0x00, 0, 0, 0, 0]);
        let path = write_temp_dds(&bytes);

        let texture = load_texture(&path).expect("DXT1 DDS should load");
        let _ = fs::remove_file(&path);

        assert_eq!(texture.width, 4);
        assert_eq!(texture.height, 4);
        assert!(!texture.has_alpha);
        assert_eq!(texture.byte_count(), 4 * 4 * 3);
        assert!(texture.data.chunks_exact(3).all(|px| px == [255, 0, 0]));
    }

    #[test]
    fn effect_texture_respects_the_remaining_pixel_budget() {
        let mut encoded = Cursor::new(Vec::new());
        DynamicImage::ImageRgb8(image::RgbImage::from_pixel(2, 2, Rgb([1, 2, 3])))
            .write_to(&mut encoded, ImageFormat::Png).unwrap();
        let path = write_temp_with_extension("png", encoded.get_ref());
        let limited = load_texture_limited(&path, 3);
        let accepted = load_texture_limited(&path, 4);
        let _ = fs::remove_file(&path);
        assert!(limited.is_err());
        assert_eq!(accepted.unwrap().byte_count(), 12);
    }

    #[test]
    fn missing_texture_error_includes_bounded_input_path() {
        let mut path = std::env::temp_dir();
        path.push(format!(
            "mmd_engine_missing_texture_{}.png",
            std::process::id()
        ));
        let message = match load_texture(&path) {
            Ok(_) => panic!("missing texture should fail"),
            Err(error) => error.to_string(),
        };
        assert!(message.contains(&path.to_string_lossy().to_string()));
        assert!(message.contains("os error 2") || message.contains("No such file"));
    }

    #[test]
    fn renamed_png_spa_texture_is_decoded_by_content() {
        let mut encoded = Cursor::new(Vec::new());
        DynamicImage::ImageRgba8(image::RgbaImage::from_pixel(1, 1, Rgba([12, 34, 56, 255])))
            .write_to(&mut encoded, ImageFormat::Png)
            .unwrap();
        let path = write_temp_with_extension("spa", encoded.get_ref());

        let texture = load_texture(&path).expect("PNG content with SPA extension should load");
        let _ = fs::remove_file(&path);

        assert_eq!((texture.width, texture.height), (1, 1));
        assert_eq!(&texture.data[..3], &[12, 34, 56]);
    }

    #[test]
    fn renamed_bmp_sph_texture_is_decoded_by_content() {
        let mut encoded = Cursor::new(Vec::new());
        DynamicImage::ImageRgb8(image::RgbImage::from_pixel(1, 1, Rgb([78, 90, 123])))
            .write_to(&mut encoded, ImageFormat::Bmp)
            .unwrap();
        let path = write_temp_with_extension("sph", encoded.get_ref());

        let texture = load_texture(&path).expect("BMP content with SPH extension should load");
        let _ = fs::remove_file(&path);

        assert_eq!((texture.width, texture.height), (1, 1));
        assert_eq!(&texture.data[..3], &[78, 90, 123]);
    }

    fn dds_texture(fourcc: &str, linear_size: u32, block: &[u8]) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(128 + block.len());
        bytes.extend_from_slice(b"DDS ");
        push_u32(&mut bytes, 124);
        push_u32(&mut bytes, 0x1007);
        push_u32(&mut bytes, 4);
        push_u32(&mut bytes, 4);
        push_u32(&mut bytes, linear_size);
        push_u32(&mut bytes, 0);
        push_u32(&mut bytes, 0);
        for _ in 0..11 {
            push_u32(&mut bytes, 0);
        }
        push_u32(&mut bytes, 32);
        push_u32(&mut bytes, 0x4);
        bytes.extend_from_slice(fourcc.as_bytes());
        push_u32(&mut bytes, 0);
        push_u32(&mut bytes, 0);
        push_u32(&mut bytes, 0);
        push_u32(&mut bytes, 0);
        push_u32(&mut bytes, 0);
        push_u32(&mut bytes, 0x1000);
        push_u32(&mut bytes, 0);
        push_u32(&mut bytes, 0);
        push_u32(&mut bytes, 0);
        push_u32(&mut bytes, 0);
        bytes.extend_from_slice(block);
        bytes
    }

    fn push_u32(bytes: &mut Vec<u8>, value: u32) {
        bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn write_temp_dds(bytes: &[u8]) -> PathBuf {
        let mut path = std::env::temp_dir();
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        path.push(format!(
            "mmd_engine_dds_test_{}_{}.dds",
            std::process::id(),
            nanos
        ));
        fs::write(&path, bytes).unwrap();
        path
    }

    fn write_temp_with_extension(extension: &str, bytes: &[u8]) -> PathBuf {
        let mut path = std::env::temp_dir();
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        path.push(format!(
            "mmd_engine_sphere_test_{}_{}.{}",
            std::process::id(),
            nanos,
            extension
        ));
        fs::write(&path, bytes).unwrap();
        path
    }
}
