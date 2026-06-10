use image::{GenericImage, GenericImageView, ImageFormat};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum StegError {
    #[error("Image processing error: {0}")]
    ImageError(#[from] image::ImageError),
    #[error("Capacity exceeded: Required {required} bytes, available {available}")]
    CapacityExceeded { required: usize, available: usize },
    #[error("Invalid steganography signature")]
    InvalidSignature,
    #[error("Invalid data format or length")]
    InvalidData,
}

const STEG_SIGNATURE: &[u8; 4] = b"ADA_";

/// Encodes a payload (like a bridge manifest or invite string) into an image via LSB.
/// The output format is PNG to avoid compression artifacts corrupting the LSB.
pub fn encode_manifest_into_image(
    image_bytes: &[u8],
    payload_string: &str,
) -> Result<Vec<u8>, StegError> {
    let payload = payload_string.as_bytes();
    let mut img = image::load_from_memory(image_bytes)?;
    let (width, height) = img.dimensions();
    let capacity = (width * height * 3) as usize / 8; // 3 channels, 1 bit per channel = 3 bits per pixel

    // Total bytes we need to hide = signature (4) + length (4) + payload length
    let total_bytes = 4 + 4 + payload.len();
    if capacity < total_bytes {
        return Err(StegError::CapacityExceeded {
            required: total_bytes,
            available: capacity,
        });
    }

    let mut data_to_hide = Vec::with_capacity(total_bytes);
    data_to_hide.extend_from_slice(STEG_SIGNATURE);
    data_to_hide.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    data_to_hide.extend_from_slice(payload);

    let mut bit_index = 0;
    let mut byte_index = 0;

    'outer: for y in 0..height {
        for x in 0..width {
            if byte_index >= data_to_hide.len() {
                break 'outer;
            }

            let mut pixel = img.get_pixel(x, y);
            for c in 0..3 {
                // RGB channels
                if byte_index < data_to_hide.len() {
                    let bit = (data_to_hide[byte_index] >> (7 - bit_index)) & 1;
                    pixel[c] = (pixel[c] & !1) | bit; // Set LSB

                    bit_index += 1;
                    if bit_index > 7 {
                        bit_index = 0;
                        byte_index += 1;
                    }
                }
            }
            img.put_pixel(x, y, pixel);
        }
    }

    let mut output = std::io::Cursor::new(Vec::new());
    // Steganography must use lossless PNG to decode
    img.write_to(&mut output, ImageFormat::Png)?;
    Ok(output.into_inner())
}

/// Decodes a hidden payload from an LSB-encoded image.
pub fn decode_manifest_from_image(image_bytes: &[u8]) -> Result<String, StegError> {
    let img = image::load_from_memory(image_bytes)?;
    let (width, height) = img.dimensions();

    let mut extracted_bytes = Vec::new();
    let mut current_byte = 0u8;
    let mut bit_index = 0;

    let mut reading_header = true;
    let mut payload_len = 0usize;

    for y in 0..height {
        for x in 0..width {
            let pixel = img.get_pixel(x, y);
            for c in 0..3 {
                let bit = pixel[c] & 1;
                current_byte = (current_byte << 1) | bit;
                bit_index += 1;

                if bit_index > 7 {
                    extracted_bytes.push(current_byte);
                    bit_index = 0;
                    current_byte = 0;

                    if reading_header && extracted_bytes.len() == 8 {
                        if &extracted_bytes[0..4] != STEG_SIGNATURE {
                            return Err(StegError::InvalidSignature);
                        }
                        let len_bytes = <[u8; 4]>::try_from(&extracted_bytes[4..8]).unwrap();
                        payload_len = u32::from_le_bytes(len_bytes) as usize;
                        reading_header = false;
                        extracted_bytes.clear(); // Reset for payload gathering

                        // Sanity check length
                        let capacity = (width * height * 3) as usize / 8;
                        if payload_len > capacity {
                            return Err(StegError::InvalidData);
                        }
                    } else if !reading_header && extracted_bytes.len() == payload_len {
                        // Fully read the payload!
                        let result = String::from_utf8(extracted_bytes)
                            .map_err(|_| StegError::InvalidData)?;
                        return Ok(result);
                    }
                }
            }
        }
    }
    Err(StegError::InvalidData)
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{ImageBuffer, RgbImage};

    #[test]
    fn test_steganography_roundtrip() {
        let img: RgbImage = ImageBuffer::new(100, 100);
        let mut cursor = std::io::Cursor::new(Vec::new());
        img.write_to(&mut cursor, ImageFormat::Png).unwrap();
        let base_image_bytes = cursor.into_inner();

        let payload = "ada-relay://192.168.1.1:443?pk=xyz";

        // Encode
        let encoded_bytes = encode_manifest_into_image(&base_image_bytes, payload).unwrap();

        // Decode
        let decoded = decode_manifest_from_image(&encoded_bytes).unwrap();
        assert_eq!(decoded, payload);
    }
}
