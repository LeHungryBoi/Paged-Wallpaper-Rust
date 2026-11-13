use crate::model::{
    normalize_offsets, starter_pages, CalibrationConfig, DarkenConfig, OriginalMedia, PageItem,
    PageTransitionSetting, WallpaperAppState, WallpaperPreset, MAX_PAGE_COUNT, MIN_PAGE_COUNT,
    SCHEMA_VERSION, SUPPORTED_FRAME_RATES, TRANSITIONS,
};
use serde_json::{json, Value};
use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::{Mutex, MutexGuard, OnceLock};

static STORE: OnceLock<Mutex<AppStore>> = OnceLock::new();

pub struct AppStore {
    root: PathBuf,
    state: WallpaperAppState,
    originals: Vec<OriginalMedia>,
    last_offset: f32,
}

impl AppStore {
    pub fn open(root: PathBuf) -> io::Result<Self> {
        fs::create_dir_all(root.join("originals"))?;
        fs::create_dir_all(root.join("cropped"))?;
        let state_path = root.join("config.json");
        let state = match fs::read_to_string(&state_path) {
            Ok(contents) => serde_json::from_str::<WallpaperAppState>(&contents).map_err(|error| {
                io::Error::new(io::ErrorKind::InvalidData, format!("invalid config.json: {error}"))
            })?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => WallpaperAppState::default(),
            Err(error) => return Err(error),
        };
        let originals_path = root.join("originals_meta.json");
        let originals = match fs::read_to_string(&originals_path) {
            Ok(contents) => serde_json::from_str::<Vec<OriginalMedia>>(&contents).map_err(|error| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("invalid originals_meta.json: {error}"),
                )
            })?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => Vec::new(),
            Err(error) => return Err(error),
        };
        let mut media_ids = std::collections::HashSet::new();
        for media in &originals {
            validate_media(media).map_err(|error| {
                io::Error::new(io::ErrorKind::InvalidData, format!("invalid media metadata: {error}"))
            })?;
            if !media_ids.insert(media.id.as_str()) {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "originals_meta.json contains duplicate media IDs",
                ));
            }
        }
        let last_offset = fs::read_to_string(root.join("last_known_offset"))
            .ok()
            .and_then(|value| value.parse::<f32>().ok())
            .filter(|value| value.is_finite() && (0.0..=1.0).contains(value))
            .unwrap_or_else(|| state.calibration.home_offset());
        let mut store = Self {
            root,
            state,
            originals,
            last_offset,
        };
        store.state.normalize();
        store.save_state()?;
        store.save_originals()?;
        Ok(store)
    }

    pub fn snapshot(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(&json!({
            "app": self.state,
            "originals": self.originals,
            "lastKnownOffset": self.last_offset,
        }))
    }

    pub fn state(&self) -> &WallpaperAppState {
        &self.state
    }

    pub fn reload_from_disk(&mut self) -> io::Result<()> {
        *self = Self::open(self.root.clone())?;
        Ok(())
    }

    pub fn original(&self, id: &str) -> Option<&OriginalMedia> {
        self.originals.iter().find(|media| media.id == id)
    }

    pub fn set_last_offset(&mut self, offset: f32) -> io::Result<()> {
        self.cache_last_offset(offset)?;
        self.persist_last_offset()
    }

    pub fn cache_last_offset(&mut self, offset: f32) -> io::Result<()> {
        if !offset.is_finite() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "offset must be finite"));
        }
        self.last_offset = offset.clamp(0.0, 1.0);
        Ok(())
    }

    pub fn persist_last_offset(&self) -> io::Result<()> {
        write_atomic(
            &self.root.join("last_known_offset"),
            self.last_offset.to_string().as_bytes(),
        )
    }

    pub fn apply_command(&mut self, command: Value) -> Result<(), String> {
        let action = command
            .get("action")
            .and_then(Value::as_str)
            .ok_or_else(|| "command is missing an action".to_string())?;
        match action {
            "setPageCount" => self.set_page_count(required_usize(&command, "count")?),
            "setHomePage" => self.set_home_page(required_usize(&command, "index")?),
            "setOffsets" => self.set_offsets(required_f32_array(&command, "offsets")?),
            "assignMedia" => self.assign_media(
                required_usize(&command, "pageIndex")?,
                required_string(&command, "mediaId")?,
            ),
            "updatePage" => self.update_page(
                required_usize(&command, "pageIndex")?,
                command
                    .get("page")
                    .cloned()
                    .ok_or_else(|| "updatePage requires a page object".to_string())?,
            ),
            "setTransition" => self.set_transition(required_string(&command, "id")?),
            "setBoundary" => self.set_boundary(
                required_usize(&command, "index")?,
                command
                    .get("value")
                    .cloned()
                    .ok_or_else(|| "setBoundary requires a value".to_string())?,
            ),
            "setSetting" => self.set_setting(
                required_string(&command, "key")?,
                command
                    .get("value")
                    .cloned()
                    .ok_or_else(|| "setSetting requires a value".to_string())?,
            ),
            "preset" => self.preset(
                required_string(&command, "operation")?,
                command.get("id").and_then(Value::as_str).unwrap_or_default(),
                command.get("name").and_then(Value::as_str).unwrap_or_default(),
            ),
            "addMedia" => self.add_media(
                serde_json::from_value(
                    command
                        .get("media")
                        .cloned()
                        .ok_or_else(|| "addMedia requires media metadata".to_string())?,
                )
                .map_err(|error| error.to_string())?,
            ),
            "removeMedia" => self.remove_media(required_string(&command, "id")?),
            "duplicateMedia" => self.duplicate_media(required_string(&command, "id")?),
            _ => Err(format!("unknown command: {action}")),
        }
    }

    fn set_page_count(&mut self, raw_count: usize) -> Result<(), String> {
        let count = raw_count.clamp(MIN_PAGE_COUNT, MAX_PAGE_COUNT);
        let old_calibration = &self.state.calibration;
        let home_index = old_calibration.home_page_index.min(count - 1);
        let mut pages = self.state.pages.clone();
        pages.resize_with(count, PageItem::default);
        pages.truncate(count);
        for (index, page) in pages.iter_mut().enumerate() {
            page.page_index = index;
            page.custom_label = if index == home_index {
                "Home".into()
            } else {
                format!("Page {}", index + 1)
            };
            if index >= self.state.pages.len() {
                page.is_blank = true;
            }
        }
        let mut state = self.state.clone();
        state.calibration = CalibrationConfig {
            page_count: count,
            home_page_index: home_index,
            auto_calibrate: old_calibration.auto_calibrate,
            custom_offsets: (0..count)
                .map(|index| index as f32 / (count - 1) as f32)
                .collect(),
        };
        state.pages = pages;
        state.transitions.resize_with(count - 1, PageTransitionSetting::default);
        state.transitions.truncate(count - 1);
        self.commit_state(state)
    }

    fn set_home_page(&mut self, index: usize) -> Result<(), String> {
        let mut state = self.state.clone();
        let home = index.min(state.calibration.page_count - 1);
        state.calibration.home_page_index = home;
        for (page_index, page) in state.pages.iter_mut().enumerate() {
            page.custom_label = if page_index == home {
                "Home".into()
            } else {
                format!("Page {}", page_index + 1)
            };
        }
        self.commit_state(state)?;
        self.set_last_offset(self.state.calibration.home_offset())
            .map_err(|error| error.to_string())
    }

    fn set_offsets(&mut self, offsets: Vec<f32>) -> Result<(), String> {
        let mut state = self.state.clone();
        state.calibration.custom_offsets =
            normalize_offsets(&offsets, state.calibration.page_count);
        self.commit_state(state)
    }

    fn assign_media(&mut self, page_index: usize, media_id: String) -> Result<(), String> {
        if page_index >= self.state.pages.len() {
            return Err("page index is out of range".into());
        }
        let media = self
            .original(&media_id)
            .ok_or_else(|| "media does not exist".to_string())?;
        let mut state = self.state.clone();
        let page = &mut state.pages[page_index];
        page.original_id = Some(media.id.clone());
        page.cropped_file_name = Some(format!("page_{page_index}.jpg"));
        page.is_already_dark = media.is_dark_detected;
        page.is_blank = false;
        self.commit_state(state)
    }

    fn update_page(&mut self, index: usize, value: Value) -> Result<(), String> {
        if index >= self.state.pages.len() {
            return Err("page index is out of range".into());
        }
        let mut page: PageItem =
            serde_json::from_value(value).map_err(|error| error.to_string())?;
        page.normalize(index);
        let mut state = self.state.clone();
        state.pages[index] = page;
        self.commit_state(state)
    }

    fn set_transition(&mut self, id: String) -> Result<(), String> {
        if !TRANSITIONS.contains(&id.as_str()) {
            return Err(format!("unsupported transition: {id}"));
        }
        let mut state = self.state.clone();
        state.transition_type = id.clone();
        state.transitions = vec![
            PageTransitionSetting {
                universal_transition_id: id.clone(),
                left_transition_id: id.clone(),
                right_transition_id: id,
                ..PageTransitionSetting::default()
            };
            state.pages.len().saturating_sub(1)
        ];
        self.commit_state(state)
    }

    fn set_boundary(&mut self, index: usize, value: Value) -> Result<(), String> {
        let mut setting: PageTransitionSetting =
            serde_json::from_value(value).map_err(|error| error.to_string())?;
        setting.normalize();
        let mut state = self.state.clone();
        if index >= state.transitions.len() {
            return Err("transition boundary index is out of range".into());
        }
        state.transitions[index] = setting;
        state.transition_type = state
            .transitions
            .first()
            .map(|transition| transition.universal_transition_id.clone())
            .unwrap_or_else(|| "scroll".into());
        self.commit_state(state)
    }

    fn set_setting(&mut self, key: String, value: Value) -> Result<(), String> {
        let mut state = self.state.clone();
        match key.as_str() {
            "resumeFadeEnabled" => state.resume_fade_enabled = as_bool(value)?,
            "showOffsetDebugOverlay" => state.show_offset_debug_overlay = as_bool(value)?,
            "showFrameDebugIndicator" => state.show_frame_debug_indicator = as_bool(value)?,
            "motionBlurEnabled" => state.motion_blur_enabled = as_bool(value)?,
            "motionBlurStrengthPercent" => {
                state.motion_blur_strength_percent = as_u32(value)?.min(200)
            }
            "offsetEasingEnabled" => state.offset_easing_enabled = as_bool(value)?,
            "offsetEasingStyle" => {
                state.offset_easing_style =
                    serde_json::from_value(value).map_err(|error| error.to_string())?
            }
            "offsetEasingLinearZonePercent" => {
                state.offset_easing_linear_zone_percent = as_u32(value)?.min(100)
            }
            "offsetEasingStrength" => state.offset_easing_strength = as_u32(value)?.min(100),
            "animationFrameRate" => {
                let rate = as_u32(value)?;
                if !SUPPORTED_FRAME_RATES.contains(&rate) {
                    return Err("unsupported animation frame rate".into());
                }
                state.animation_frame_rate = rate;
            }
            "darken" => {
                state.darken =
                    serde_json::from_value::<DarkenConfig>(value).map_err(|error| error.to_string())?
            }
            "backupFolderPath" => {
                state.backup_folder_path = value.as_str().map(|value| value.to_string())
            }
            "autoBackupEnabled" => state.auto_backup_enabled = as_bool(value)?,
            _ => return Err(format!("unsupported setting: {key}")),
        }
        self.commit_state(state)
    }

    fn preset(&mut self, operation: String, id: &str, name: &str) -> Result<(), String> {
        let mut state = self.state.clone();
        match operation.as_str() {
            "select" => {
                let preset = state
                    .presets
                    .iter()
                    .find(|preset| preset.id == id)
                    .cloned()
                    .ok_or_else(|| "preset does not exist".to_string())?;
                state.pages = preset.pages;
                state.transitions = preset.transitions;
                state.active_preset_id = preset.id;
            }
            "duplicate" => {
                let clean_name = clean_preset_name(name)?;
                let source = state
                    .presets
                    .iter()
                    .find(|preset| preset.id == id)
                    .cloned()
                    .ok_or_else(|| "preset does not exist".to_string())?;
                let id = create_id();
                let preset = WallpaperPreset {
                    id: id.clone(),
                    name: clean_name,
                    pages: source.pages,
                    transitions: source.transitions,
                };
                state.presets.push(preset.clone());
                state.pages = preset.pages;
                state.transitions = preset.transitions;
                state.active_preset_id = id;
            }
            "rename" => {
                let clean_name = clean_preset_name(name)?;
                let preset = state
                    .presets
                    .iter_mut()
                    .find(|preset| preset.id == id)
                    .ok_or_else(|| "preset does not exist".to_string())?;
                preset.name = clean_name;
            }
            "delete" => {
                if state.presets.len() <= 1 {
                    return Err("the final preset cannot be deleted".into());
                }
                let previous_len = state.presets.len();
                state.presets.retain(|preset| preset.id != id);
                if state.presets.len() == previous_len {
                    return Err("preset does not exist".into());
                }
                if state.active_preset_id == id {
                    let fallback = state.presets[0].clone();
                    state.pages = fallback.pages;
                    state.transitions = fallback.transitions;
                    state.active_preset_id = fallback.id;
                }
            }
            _ => return Err(format!("unsupported preset operation: {operation}")),
        }
        self.commit_state(state)
    }

    fn add_media(&mut self, media: OriginalMedia) -> Result<(), String> {
        validate_media(&media)?;
        if self.originals.iter().any(|item| item.id == media.id) {
            return Err("media ID already exists".into());
        }
        if !self.root.join("originals").join(&media.file_name).is_file() {
            return Err("imported media file is missing".into());
        }
        let mut originals = self.originals.clone();
        originals.push(media);
        self.commit_originals(originals)
    }

    fn remove_media(&mut self, id: String) -> Result<(), String> {
        let mut originals = self.originals.clone();
        let removed = originals
            .iter()
            .find(|media| media.id == id)
            .cloned()
            .ok_or_else(|| "media does not exist".to_string())?;
        originals.retain(|media| media.id != id);
        let mut state = self.state.clone();
        for page in &mut state.pages {
            if page.original_id.as_deref() == Some(id.as_str()) {
                page.original_id = None;
                page.cropped_file_name = None;
                page.is_already_dark = false;
            }
        }
        for preset in &mut state.presets {
            for page in &mut preset.pages {
                if page.original_id.as_deref() == Some(id.as_str()) {
                    page.original_id = None;
                    page.cropped_file_name = None;
                    page.is_already_dark = false;
                }
            }
        }
        self.commit_state(state)?;
        self.commit_originals(originals)?;
        let file = self.root.join("originals").join(removed.file_name);
        if file.exists() {
            fs::remove_file(file).map_err(|error| error.to_string())?;
        }
        Ok(())
    }

    fn duplicate_media(&mut self, id: String) -> Result<(), String> {
        let source = self
            .original(&id)
            .cloned()
            .ok_or_else(|| "media does not exist".to_string())?;
        let new_id = create_id();
        let file_name = format!("{new_id}.jpg");
        fs::copy(
            self.root.join("originals").join(&source.file_name),
            self.root.join("originals").join(&file_name),
        )
        .map_err(|error| error.to_string())?;
        let mut duplicate = source;
        duplicate.id = new_id;
        duplicate.file_name = file_name.clone();
        duplicate.date_added = now_millis();
        let mut originals = self.originals.clone();
        originals.push(duplicate);
        if let Err(error) = self.commit_originals(originals) {
            fs::remove_file(self.root.join("originals").join(file_name))
                .map_err(|cleanup| format!("{error}; could not remove duplicate file: {cleanup}"))?;
            return Err(error);
        }
        Ok(())
    }

    fn commit_state(&mut self, mut state: WallpaperAppState) -> Result<(), String> {
        state.normalize();
        state.presets = state
            .presets
            .iter()
            .map(|preset| {
                if preset.id == state.active_preset_id {
                    WallpaperPreset {
                        pages: state.pages.clone(),
                        transitions: state.transitions.clone(),
                        ..preset.clone()
                    }
                } else {
                    preset.clone()
                }
            })
            .collect();
        let serialized =
            serde_json::to_vec_pretty(&state).map_err(|error| error.to_string())?;
        write_atomic(&self.root.join("config.json"), &serialized)
            .map_err(|error| error.to_string())?;
        self.state = state;
        Ok(())
    }

    fn commit_originals(&mut self, originals: Vec<OriginalMedia>) -> Result<(), String> {
        let serialized =
            serde_json::to_vec_pretty(&originals).map_err(|error| error.to_string())?;
        write_atomic(&self.root.join("originals_meta.json"), &serialized)
            .map_err(|error| error.to_string())?;
        self.originals = originals;
        Ok(())
    }

    fn save_state(&self) -> io::Result<()> {
        let serialized = serde_json::to_vec_pretty(&self.state)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        write_atomic(&self.root.join("config.json"), &serialized)
    }

    fn save_originals(&self) -> io::Result<()> {
        let serialized = serde_json::to_vec_pretty(&self.originals)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        write_atomic(&self.root.join("originals_meta.json"), &serialized)
    }
}

pub fn validate_backup(root: PathBuf) -> io::Result<()> {
    AppStore::open(root).map(|_| ())
}

pub fn initialize(root: PathBuf) -> io::Result<()> {
    if STORE.get().is_some() {
        return Ok(());
    }
    let store = AppStore::open(root)?;
    let _ = STORE.set(Mutex::new(store));
    Ok(())
}

pub fn lock_store() -> Result<MutexGuard<'static, AppStore>, String> {
    STORE
        .get()
        .ok_or_else(|| "native core has not been initialized".to_string())?
        .lock()
        .map_err(|_| "native core state lock is poisoned".to_string())
}

fn write_atomic(path: &Path, contents: &[u8]) -> io::Result<()> {
    let temporary = path.with_extension("tmp");
    let mut file = File::create(&temporary)?;
    file.write_all(contents)?;
    file.sync_all()?;
    fs::rename(temporary, path)
}

fn normalize_file_name(name: &str) -> bool {
    let path = Path::new(name);
    !name.is_empty()
        && path.file_name().and_then(|part| part.to_str()) == Some(name)
        && path.components().all(|component| matches!(component, Component::Normal(_)))
}

fn validate_media(media: &OriginalMedia) -> Result<(), String> {
    if media.id.is_empty()
        || !media
            .id
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || character == '-' || character == '_')
    {
        return Err("media ID contains invalid characters".into());
    }
    if !normalize_file_name(&media.file_name) {
        return Err("media file name must be a plain file name".into());
    }
    Ok(())
}

fn required_string(command: &Value, key: &str) -> Result<String, String> {
    command
        .get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .ok_or_else(|| format!("{key} must be a non-empty string"))
}

fn required_usize(command: &Value, key: &str) -> Result<usize, String> {
    command
        .get(key)
        .and_then(Value::as_u64)
        .and_then(|value| usize::try_from(value).ok())
        .ok_or_else(|| format!("{key} must be a non-negative integer"))
}

fn required_f32_array(command: &Value, key: &str) -> Result<Vec<f32>, String> {
    command
        .get(key)
        .and_then(Value::as_array)
        .ok_or_else(|| format!("{key} must be an array"))?
        .iter()
        .map(|value| {
            value
                .as_f64()
                .map(|value| value as f32)
                .ok_or_else(|| format!("{key} entries must be numbers"))
        })
        .collect()
}

fn as_bool(value: Value) -> Result<bool, String> {
    value.as_bool().ok_or_else(|| "value must be boolean".into())
}

fn as_u32(value: Value) -> Result<u32, String> {
    value
        .as_u64()
        .and_then(|value| u32::try_from(value).ok())
        .ok_or_else(|| "value must be a non-negative integer".into())
}

fn clean_preset_name(name: &str) -> Result<String, String> {
    let value = name.trim().chars().take(40).collect::<String>();
    if value.is_empty() {
        Err("preset name must not be empty".into())
    } else {
        Ok(value)
    }
}

fn create_id() -> String {
    static NEXT_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let sequence = NEXT_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    format!("{timestamp:x}-{:x}-{sequence:x}", std::process::id())
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn page_count_keeps_home_and_resizes_transitions() {
        let directory = tempfile::tempdir().unwrap();
        let mut store = AppStore::open(directory.path().to_path_buf()).unwrap();

        store.apply_command(json!({"action":"setPageCount","count":5})).unwrap();

        assert_eq!(store.state.calibration.page_count, 5);
        assert_eq!(store.state.pages.len(), 5);
        assert_eq!(store.state.transitions.len(), 4);
    }

    #[test]
    fn offsets_stay_ordered_and_pinned_to_edges() {
        let directory = tempfile::tempdir().unwrap();
        let mut store = AppStore::open(directory.path().to_path_buf()).unwrap();

        store.apply_command(json!({
            "action":"setOffsets",
            "offsets":[0.2,0.8,0.1]
        })).unwrap();

        let offsets = &store.state.calibration.custom_offsets;
        assert_eq!(offsets.first().copied(), Some(0.0));
        assert_eq!(offsets.last().copied(), Some(1.0));
        assert!(offsets.windows(2).all(|pair| pair[0] <= pair[1]));
    }

    #[test]
    fn rejects_media_paths_that_escape_originals_directory() {
        let media = OriginalMedia {
            id: "safe-id".into(),
            file_name: "../outside.jpg".into(),
            ..OriginalMedia::default()
        };

        assert!(validate_media(&media).is_err());
    }

    #[test]
    fn removing_media_clears_all_preset_references() {
        let directory = tempfile::tempdir().unwrap();
        let mut store = AppStore::open(directory.path().to_path_buf()).unwrap();
        let media_path = directory.path().join("originals").join("image.jpg");
        fs::write(&media_path, b"image").unwrap();
        store
            .apply_command(json!({
                "action":"addMedia",
                "media":{
                    "id":"image-id",
                    "fileName":"image.jpg",
                    "dateAdded":1,
                    "width":100,
                    "height":100,
                    "averageLuminance":0.5,
                    "isDarkDetected":false
                }
            }))
            .unwrap();
        store
            .apply_command(json!({
                "action":"assignMedia",
                "pageIndex":0,
                "mediaId":"image-id"
            }))
            .unwrap();
        store
            .apply_command(json!({
                "action":"preset",
                "operation":"duplicate",
                "id":"default",
                "name":"Copy"
            }))
            .unwrap();

        store
            .apply_command(json!({"action":"removeMedia","id":"image-id"}))
            .unwrap();

        assert!(store.state.pages.iter().all(|page| page.original_id.is_none()));
        assert!(store
            .state
            .presets
            .iter()
            .flat_map(|preset| &preset.pages)
            .all(|page| page.original_id.is_none()));
        assert!(store.originals.is_empty());
        assert!(!media_path.exists());
    }
}
