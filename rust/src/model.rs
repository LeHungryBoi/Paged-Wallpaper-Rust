use serde::{Deserialize, Deserializer, Serialize, Serializer};

pub const SCHEMA_VERSION: u32 = 4;
pub const MIN_PAGE_COUNT: usize = 2;
pub const MAX_PAGE_COUNT: usize = 7;
pub const SUPPORTED_FRAME_RATES: [u32; 5] = [60, 90, 120, 144, 165];
pub const TRANSITIONS: [&str; 8] = [
    "scroll",
    "parallax",
    "parallax_classic",
    "fade",
    "soft_blur",
    "depth",
    "slide_over",
    "cube",
];

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DarkenTrigger {
    Off,
    ExtraDim,
    DarkTheme,
    Scheduled,
    Always,
}

impl Default for DarkenTrigger {
    fn default() -> Self {
        Self::ExtraDim
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum OffsetEasingStyle {
    Symmetric,
    FastStartSlowEnd,
}

impl Default for OffsetEasingStyle {
    fn default() -> Self {
        Self::Symmetric
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CropFillMode {
    Crop,
    Blur,
    Glass,
}

impl Default for CropFillMode {
    fn default() -> Self {
        Self::Blur
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(default)]
pub struct PageItem {
    pub page_index: usize,
    #[serde(
        default,
        deserialize_with = "deserialize_optional_string",
        serialize_with = "serialize_optional_string"
    )]
    pub original_id: Option<String>,
    #[serde(
        default,
        deserialize_with = "deserialize_optional_string",
        serialize_with = "serialize_optional_string"
    )]
    pub cropped_file_name: Option<String>,
    pub is_already_dark: bool,
    pub custom_label: String,
    pub crop_fill_mode: CropFillMode,
    pub blur_amount: f32,
    pub crop_pan_x: f32,
    pub crop_pan_y: f32,
    pub crop_scale: f32,
    pub parallax_amount: f32,
    pub is_blank: bool,
    #[serde(default, skip_serializing)]
    pub slow_parallax: bool,
    #[serde(default = "default_image_speed", skip_serializing)]
    pub parallax_image_speed: f32,
}

impl Default for PageItem {
    fn default() -> Self {
        Self {
            page_index: 0,
            original_id: None,
            cropped_file_name: None,
            is_already_dark: false,
            custom_label: String::new(),
            crop_fill_mode: CropFillMode::default(),
            blur_amount: 0.65,
            crop_pan_x: 0.0,
            crop_pan_y: 0.0,
            crop_scale: 1.0,
            parallax_amount: 0.0,
            is_blank: false,
            slow_parallax: false,
            parallax_image_speed: default_image_speed(),
        }
    }
}

impl PageItem {
    pub fn normalize(&mut self, index: usize) {
        self.page_index = index;
        self.blur_amount = finite_or(self.blur_amount, 0.65).clamp(0.0, 1.0);
        self.crop_pan_x = finite_or(self.crop_pan_x, 0.0).clamp(-1.0, 1.0);
        self.crop_pan_y = finite_or(self.crop_pan_y, 0.0).clamp(-1.0, 1.0);
        self.crop_scale = finite_or(self.crop_scale, 1.0).clamp(1.0, 3.0);
        self.parallax_amount = finite_or(self.parallax_amount, 0.0).clamp(0.0, 1.0);
        self.parallax_image_speed = finite_or(self.parallax_image_speed, 0.5).clamp(0.0, 1.0);
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(default)]
pub struct ParallaxMotionSettings {
    pub slow_image_motion_enabled: bool,
    pub image_speed: f32,
}

impl Default for ParallaxMotionSettings {
    fn default() -> Self {
        Self {
            slow_image_motion_enabled: false,
            image_speed: 0.5,
        }
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PageTransitionSetting {
    pub universal_transition_id: String,
    pub left_transition_id: String,
    pub right_transition_id: String,
    pub separate_directions: bool,
    pub universal_parallax_motion: ParallaxMotionSettings,
    pub left_parallax_motion: ParallaxMotionSettings,
    pub right_parallax_motion: ParallaxMotionSettings,
}

#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(default)]
struct PageTransitionSettingData {
    #[serde(alias = "transitionId", alias = "forwardTransitionId")]
    universal_transition_id: Option<String>,
    #[serde(alias = "reverseTransitionId")]
    left_transition_id: Option<String>,
    right_transition_id: Option<String>,
    separate_directions: Option<bool>,
    universal_parallax_motion: Option<ParallaxMotionSettings>,
    left_parallax_motion: Option<ParallaxMotionSettings>,
    right_parallax_motion: Option<ParallaxMotionSettings>,
}

impl<'de> Deserialize<'de> for PageTransitionSetting {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let data = PageTransitionSettingData::deserialize(deserializer)?;
        let forward = data
            .universal_transition_id
            .unwrap_or_else(|| "scroll".into());
        Ok(Self {
            universal_transition_id: forward.clone(),
            left_transition_id: data
                .left_transition_id
                .unwrap_or_else(|| forward.clone()),
            right_transition_id: data
                .right_transition_id
                .unwrap_or_else(|| forward.clone()),
            separate_directions: data.separate_directions.unwrap_or(false),
            universal_parallax_motion: data.universal_parallax_motion.unwrap_or_default(),
            left_parallax_motion: data.left_parallax_motion.unwrap_or_default(),
            right_parallax_motion: data.right_parallax_motion.unwrap_or_default(),
        })
    }
}

impl Default for PageTransitionSetting {
    fn default() -> Self {
        Self {
            universal_transition_id: "scroll".into(),
            left_transition_id: "scroll".into(),
            right_transition_id: "scroll".into(),
            separate_directions: false,
            universal_parallax_motion: ParallaxMotionSettings::default(),
            left_parallax_motion: ParallaxMotionSettings::default(),
            right_parallax_motion: ParallaxMotionSettings::default(),
        }
    }
}

impl PageTransitionSetting {
    pub fn transition_id(&self, forward: bool) -> &str {
        if !self.separate_directions {
            &self.universal_transition_id
        } else if forward {
            &self.right_transition_id
        } else {
            &self.left_transition_id
        }
    }

    pub fn parallax_motion(&self, forward: bool) -> &ParallaxMotionSettings {
        if !self.separate_directions {
            &self.universal_parallax_motion
        } else if forward {
            &self.right_parallax_motion
        } else {
            &self.left_parallax_motion
        }
    }

    pub fn normalize(&mut self) {
        for id in [
            &mut self.universal_transition_id,
            &mut self.left_transition_id,
            &mut self.right_transition_id,
        ] {
            if !TRANSITIONS.contains(&id.as_str()) {
                *id = "scroll".into();
            }
        }
        for motion in [
            &mut self.universal_parallax_motion,
            &mut self.left_parallax_motion,
            &mut self.right_parallax_motion,
        ] {
            motion.image_speed = finite_or(motion.image_speed, 0.5).clamp(0.0, 1.0);
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(default)]
pub struct WallpaperPreset {
    pub id: String,
    pub name: String,
    pub pages: Vec<PageItem>,
    pub transitions: Vec<PageTransitionSetting>,
}

impl Default for WallpaperPreset {
    fn default() -> Self {
        Self {
            id: "default".into(),
            name: "Default".into(),
            pages: Vec::new(),
            transitions: Vec::new(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(default)]
pub struct CalibrationConfig {
    pub page_count: usize,
    pub home_page_index: usize,
    pub auto_calibrate: bool,
    pub custom_offsets: Vec<f32>,
}

impl Default for CalibrationConfig {
    fn default() -> Self {
        Self {
            page_count: 3,
            home_page_index: 1,
            auto_calibrate: false,
            custom_offsets: vec![0.0, 0.5, 1.0],
        }
    }
}

impl CalibrationConfig {
    pub fn home_offset(&self) -> f32 {
        self.custom_offsets
            .get(self.home_page_index)
            .copied()
            .unwrap_or_else(|| {
                if self.page_count <= 1 {
                    0.5
                } else {
                    self.home_page_index as f32 / (self.page_count - 1) as f32
                }
            })
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(default)]
pub struct DarkenConfig {
    pub trigger_mode: DarkenTrigger,
    pub auto_detect_dark: bool,
    pub luminance_threshold: f32,
    pub dim_factor: f32,
    pub schedule_start_hour: u32,
    pub schedule_start_minute: u32,
    pub schedule_end_hour: u32,
    pub schedule_end_minute: u32,
}

impl Default for DarkenConfig {
    fn default() -> Self {
        Self {
            trigger_mode: DarkenTrigger::default(),
            auto_detect_dark: true,
            luminance_threshold: 0.35,
            dim_factor: 0.4,
            schedule_start_hour: 20,
            schedule_start_minute: 0,
            schedule_end_hour: 7,
            schedule_end_minute: 0,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(default)]
pub struct OriginalMedia {
    pub id: String,
    pub file_name: String,
    pub date_added: u64,
    pub width: u32,
    pub height: u32,
    pub average_luminance: f32,
    pub is_dark_detected: bool,
}

impl Default for OriginalMedia {
    fn default() -> Self {
        Self {
            id: String::new(),
            file_name: String::new(),
            date_added: 0,
            width: 1080,
            height: 2400,
            average_luminance: 0.5,
            is_dark_detected: false,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(default)]
pub struct WallpaperAppState {
    pub pages: Vec<PageItem>,
    pub calibration: CalibrationConfig,
    pub transition_type: String,
    pub resume_fade_enabled: bool,
    pub darken: DarkenConfig,
    #[serde(
        default,
        deserialize_with = "deserialize_optional_string",
        serialize_with = "serialize_optional_string"
    )]
    pub backup_folder_path: Option<String>,
    pub auto_backup_enabled: bool,
    pub show_offset_debug_overlay: bool,
    pub show_frame_debug_indicator: bool,
    pub motion_blur_enabled: bool,
    pub motion_blur_strength_percent: u32,
    pub offset_easing_enabled: bool,
    pub offset_easing_style: OffsetEasingStyle,
    pub offset_easing_linear_zone_percent: u32,
    pub offset_easing_strength: u32,
    pub animation_frame_rate: u32,
    pub presets: Vec<WallpaperPreset>,
    pub active_preset_id: String,
    pub transitions: Vec<PageTransitionSetting>,
    pub preset_needs_save: bool,
    pub schema_version: u32,
}

impl Default for WallpaperAppState {
    fn default() -> Self {
        let calibration = CalibrationConfig::default();
        let pages = starter_pages(calibration.page_count, calibration.home_page_index);
        let transitions = vec![PageTransitionSetting::default(); calibration.page_count - 1];
        let preset = WallpaperPreset {
            id: "default".into(),
            name: "Default".into(),
            pages: pages.clone(),
            transitions: transitions.clone(),
        };
        Self {
            pages,
            calibration,
            transition_type: "scroll".into(),
            resume_fade_enabled: false,
            darken: DarkenConfig::default(),
            backup_folder_path: None,
            auto_backup_enabled: false,
            show_offset_debug_overlay: false,
            show_frame_debug_indicator: false,
            motion_blur_enabled: true,
            motion_blur_strength_percent: 100,
            offset_easing_enabled: false,
            offset_easing_style: OffsetEasingStyle::default(),
            offset_easing_linear_zone_percent: 60,
            offset_easing_strength: 50,
            animation_frame_rate: 60,
            presets: vec![preset],
            active_preset_id: "default".into(),
            transitions,
            preset_needs_save: false,
            schema_version: SCHEMA_VERSION,
        }
    }
}

impl WallpaperAppState {
    pub fn normalize(&mut self) {
        let count = self
            .calibration
            .page_count
            .clamp(MIN_PAGE_COUNT, MAX_PAGE_COUNT);
        self.calibration.page_count = count;
        self.calibration.home_page_index = self.calibration.home_page_index.min(count - 1);
        self.calibration.custom_offsets = normalize_offsets(&self.calibration.custom_offsets, count);
        self.pages.resize_with(count, PageItem::default);
        self.pages.truncate(count);
        for (index, page) in self.pages.iter_mut().enumerate() {
            page.normalize(index);
            if page
                .cropped_file_name
                .as_deref()
                .is_some_and(|name| !is_safe_file_name(name))
            {
                page.cropped_file_name = None;
            }
            if page.custom_label.is_empty() {
                page.custom_label = format!("Page {}", index + 1);
            }
        }
        if let Some(home) = self.pages.get_mut(self.calibration.home_page_index) {
            home.custom_label = "Home".into();
        }
        let fallback_transition = self.transition_type.clone();
        self.transitions.resize_with(count - 1, || PageTransitionSetting {
            universal_transition_id: fallback_transition.clone(),
            left_transition_id: fallback_transition.clone(),
            right_transition_id: fallback_transition.clone(),
            ..PageTransitionSetting::default()
        });
        self.transitions.truncate(count - 1);
        for transition in &mut self.transitions {
            transition.normalize();
        }
        migrate_legacy_parallax(&self.pages, &mut self.transitions);
        if !TRANSITIONS.contains(&self.transition_type.as_str()) {
            self.transition_type = "scroll".into();
        }
        self.animation_frame_rate = if SUPPORTED_FRAME_RATES.contains(&self.animation_frame_rate) {
            self.animation_frame_rate
        } else {
            60
        };
        self.motion_blur_strength_percent = self.motion_blur_strength_percent.min(200);
        self.offset_easing_linear_zone_percent = self.offset_easing_linear_zone_percent.min(100);
        self.offset_easing_strength = self.offset_easing_strength.min(100);
        self.darken.luminance_threshold = finite_or(self.darken.luminance_threshold, 0.35).clamp(0.0, 1.0);
        self.darken.dim_factor = finite_or(self.darken.dim_factor, 0.4).clamp(0.1, 0.9);
        self.darken.schedule_start_hour = self.darken.schedule_start_hour.min(23);
        self.darken.schedule_end_hour = self.darken.schedule_end_hour.min(23);
        self.darken.schedule_start_minute = self.darken.schedule_start_minute.min(59);
        self.darken.schedule_end_minute = self.darken.schedule_end_minute.min(59);
        for preset in &mut self.presets {
            normalize_preset(preset, count);
        }
        if self.presets.is_empty() {
            self.presets.push(WallpaperPreset {
                id: "default".into(),
                name: "Default".into(),
                pages: self.pages.clone(),
                transitions: self.transitions.clone(),
            });
        }
        if !self.presets.iter().any(|preset| preset.id == self.active_preset_id) {
            self.active_preset_id = self.presets[0].id.clone();
        }
        self.schema_version = SCHEMA_VERSION;
        self.preset_needs_save = false;
        if let Some(active) = self.presets.iter_mut().find(|preset| preset.id == self.active_preset_id) {
            active.pages = self.pages.clone();
            active.transitions = self.transitions.clone();
        }
    }
}

pub fn starter_pages(count: usize, home_index: usize) -> Vec<PageItem> {
    (0..count)
        .map(|index| PageItem {
            page_index: index,
            cropped_file_name: Some(format!("starter_page_{index}.jpg")),
            custom_label: if index == home_index {
                "Home".into()
            } else {
                format!("Page {}", index + 1)
            },
            ..PageItem::default()
        })
        .collect()
}

pub fn normalize_offsets(offsets: &[f32], count: usize) -> Vec<f32> {
    if offsets.len() != count {
        return (0..count)
            .map(|index| index as f32 / (count - 1).max(1) as f32)
            .collect();
    }
    let mut result = Vec::with_capacity(count);
    let mut previous = 0.0_f32;
    for (index, offset) in offsets.iter().enumerate() {
        let minimum = if index == 0 { 0.0 } else { previous };
        let maximum = 1.0 - (count - index - 1) as f32 * 0.001;
        let value = finite_or(*offset, index as f32 / (count - 1) as f32)
            .clamp(minimum, maximum.max(minimum));
        result.push(value);
        previous = value;
    }
    result[0] = 0.0;
    result[count - 1] = 1.0;
    result
}

fn normalize_preset(preset: &mut WallpaperPreset, count: usize) {
    preset.pages.resize_with(count, PageItem::default);
    preset.pages.truncate(count);
    for (index, page) in preset.pages.iter_mut().enumerate() {
        page.normalize(index);
        if page
            .cropped_file_name
            .as_deref()
            .is_some_and(|name| !is_safe_file_name(name))
        {
            page.cropped_file_name = None;
        }
        if page.custom_label.is_empty() {
            page.custom_label = format!("Page {}", index + 1);
        }
    }
    preset.transitions.resize_with(count - 1, PageTransitionSetting::default);
    preset.transitions.truncate(count - 1);
    for transition in &mut preset.transitions {
        transition.normalize();
    }
    migrate_legacy_parallax(&preset.pages, &mut preset.transitions);
}

fn finite_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() { value } else { fallback }
}

fn default_image_speed() -> f32 {
    0.5
}

fn migrate_legacy_parallax(pages: &[PageItem], transitions: &mut [PageTransitionSetting]) {
    for (boundary, transition) in transitions.iter_mut().enumerate() {
        let speeds = [pages.get(boundary), pages.get(boundary + 1)]
            .into_iter()
            .flatten()
            .filter(|page| page.slow_parallax)
            .map(|page| page.parallax_image_speed)
            .collect::<Vec<_>>();
        if speeds.is_empty() {
            continue;
        }
        let motion = ParallaxMotionSettings {
            slow_image_motion_enabled: true,
            image_speed: speeds.iter().sum::<f32>() / speeds.len() as f32,
        };
        transition.universal_parallax_motion = motion.clone();
        transition.left_parallax_motion = motion.clone();
        transition.right_parallax_motion = motion;
    }
}

fn is_safe_file_name(name: &str) -> bool {
    !name.is_empty()
        && name != "."
        && name != ".."
        && !name.contains('/')
        && !name.contains('\\')
}

fn deserialize_optional_string<'de, D>(deserializer: D) -> Result<Option<String>, D::Error>
where
    D: Deserializer<'de>,
{
    Option::<String>::deserialize(deserializer).map(|value| value.filter(|value| !value.is_empty()))
}

fn serialize_optional_string<S>(value: &Option<String>, serializer: S) -> Result<S::Ok, S::Error>
where
    S: Serializer,
{
    serializer.serialize_str(value.as_deref().unwrap_or(""))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn reads_legacy_empty_strings_for_optional_fields() {
        let state: WallpaperAppState = serde_json::from_value(json!({
            "pages": [{
                "pageIndex": 0,
                "originalId": "",
                "croppedFileName": ""
            }],
            "backupFolderPath": ""
        }))
        .unwrap();

        assert_eq!(state.pages[0].original_id, None);
        assert_eq!(state.pages[0].cropped_file_name, None);
        assert_eq!(state.backup_folder_path, None);
    }

    #[test]
    fn serializes_optional_strings_in_legacy_compatible_form() {
        let state = WallpaperAppState::default();
        let value = serde_json::to_value(state).unwrap();

        assert_eq!(value["backupFolderPath"], "");
        assert_eq!(value["pages"][0]["originalId"], "");
        assert_eq!(value["pages"][0]["croppedFileName"], "starter_page_0.jpg");
    }

    #[test]
    fn migrates_legacy_page_parallax_to_boundary_settings() {
        let mut state: WallpaperAppState = serde_json::from_value(json!({
            "pages": [
                {"pageIndex": 0, "slowParallax": true, "parallaxImageSpeed": 0.2},
                {"pageIndex": 1, "slowParallax": true, "parallaxImageSpeed": 0.8},
                {"pageIndex": 2}
            ]
        }))
        .unwrap();
        state.normalize();

        assert!(state.transitions[0].universal_parallax_motion.slow_image_motion_enabled);
        assert!((state.transitions[0].universal_parallax_motion.image_speed - 0.5).abs() < 0.001);
        assert!(state.transitions[1].universal_parallax_motion.slow_image_motion_enabled);
        assert!((state.transitions[1].universal_parallax_motion.image_speed - 0.8).abs() < 0.001);
    }

    #[test]
    fn legacy_directional_transition_defaults_reverse_to_forward() {
        let transition: PageTransitionSetting = serde_json::from_value(json!({
            "forwardTransitionId": "fade",
            "separateDirections": true
        }))
        .unwrap();

        assert_eq!(transition.universal_transition_id, "fade");
        assert_eq!(transition.right_transition_id, "fade");
        assert_eq!(transition.left_transition_id, "fade");
    }
}
