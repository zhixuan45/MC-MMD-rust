use std::path::PathBuf;
use std::process::ExitCode;

use mmd_engine::vmd_smoothing::{
    parse_group_config, process_directory_grouped, smooth_file_grouped, BatchStatus, BoneSelection,
    SmoothingGroup, SmoothingOptions,
};

const HELP: &str = "VMD 动作平滑工具\n\
用法：vmd_smooth --input <文件或目录> --output <文件或目录> [选项]\n\
选项：\n\
  --recursive          递归处理输入目录中的 VMD\n\
  --strength <0..1>    平滑强度（默认 0.35）\n\
  --radius <帧数>      平滑窗口半径（默认 2）\n\
  --loop               将动作视为循环片段\n\
  --scope <范围>       upper / all（含 IK）/ all-except-ik（默认 upper）\n\
  --bone <名称>        指定骨骼，可重复；指定后覆盖 --scope\n\
  --groups <JSON文件>  使用多个独立平滑组，后面的启用组优先\n\
  -h, --help           显示帮助\n\
--groups 与 strength/radius/loop/scope/bone 不混用。\n\
已有目标文件会跳过；输出目录会保留输入相对路径。";

fn main() -> ExitCode {
    match run() {
        Ok(failed) => {
            if failed {
                ExitCode::from(1)
            } else {
                ExitCode::SUCCESS
            }
        }
        Err(error) => {
            eprintln!("错误：{error}\n\n{HELP}");
            ExitCode::from(2)
        }
    }
}

fn run() -> Result<bool, String> {
    let mut args = std::env::args().skip(1);
    let mut input = None;
    let mut output = None;
    let mut recursive = false;
    let defaults = SmoothingOptions::default();
    let mut strength = defaults.strength;
    let mut radius = defaults.radius;
    let mut looped = false;
    let mut scope = defaults.selection;
    let mut bones = Vec::new();
    let mut group_file = None;
    let mut single_parameters_supplied = false;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "-h" | "--help" => {
                println!("{HELP}");
                return Ok(false);
            }
            "--input" => input = Some(PathBuf::from(value(&mut args, "--input")?)),
            "--output" => output = Some(PathBuf::from(value(&mut args, "--output")?)),
            "--recursive" => recursive = true,
            "--groups" => group_file = Some(PathBuf::from(value(&mut args, "--groups")?)),
            "--loop" => {
                single_parameters_supplied = true;
                looped = true;
            }
            "--strength" => {
                single_parameters_supplied = true;
                strength = value(&mut args, "--strength")?
                    .parse()
                    .map_err(|_| "--strength 需要数字".to_string())?
            }
            "--radius" => {
                single_parameters_supplied = true;
                radius = value(&mut args, "--radius")?
                    .parse()
                    .map_err(|_| "--radius 需要非负整数".to_string())?
            }
            "--scope" => {
                single_parameters_supplied = true;
                scope = match value(&mut args, "--scope")?.as_str() {
                    "upper" => BoneSelection::UpperBody,
                    "all" => BoneSelection::All,
                    "all-except-ik" => BoneSelection::AllExceptIk,
                    other => {
                        return Err(format!(
                            "不支持的范围：{other}（可选 upper、all 或 all-except-ik）"
                        ))
                    }
                }
            }
            "--bone" => {
                single_parameters_supplied = true;
                bones.push(value(&mut args, "--bone")?);
            }
            other => return Err(format!("未知参数：{other}")),
        }
    }
    let input = input.ok_or("缺少 --input")?;
    let output = output.ok_or("缺少 --output")?;
    let selection = if bones.is_empty() {
        scope
    } else {
        BoneSelection::Named(bones)
    };
    let options = SmoothingOptions {
        strength,
        radius,
        looped,
        selection,
    };
    options.validate().map_err(|e| e.to_string())?;
    // 分组参数整体替代单组参数，避免部分选项被静默忽略。
    let groups = if let Some(path) = group_file {
        if single_parameters_supplied {
            return Err("--groups 不能与单组平滑参数混用".into());
        }
        let config = std::fs::read_to_string(&path)
            .map_err(|error| format!("读取分组配置失败 {}: {error}", path.display()))?;
        parse_group_config(&config).map_err(|error| error.to_string())?
    } else {
        vec![SmoothingGroup {
            name: "默认组".into(),
            enabled: true,
            options,
        }]
    };
    if input.is_dir() {
        let report = process_directory_grouped(&input, &output, &groups, recursive, |progress| {
            match &progress.entry.status {
                BatchStatus::Processed(result) => {
                    println!(
                        "[{}/{}] 处理完成：{} → {}（{} 个所选轨道关键帧）",
                        progress.completed,
                        progress.total,
                        progress.entry.input.display(),
                        progress.entry.output.display(),
                        result.output_keys
                    );
                    print_warnings(&progress.entry.output, &result.warnings);
                }
                BatchStatus::Skipped(reason) => println!(
                    "[{}/{}] 跳过：{}（{reason}）",
                    progress.completed,
                    progress.total,
                    progress.entry.output.display()
                ),
                BatchStatus::Failed(reason) => eprintln!(
                    "[{}/{}] 失败：{}（{reason}）",
                    progress.completed,
                    progress.total,
                    progress.entry.input.display()
                ),
            }
        })
        .map_err(|e| e.to_string())?;
        let (done, skipped, failed) = report.counts();
        println!("批处理结束：成功 {done}，跳过 {skipped}，失败 {failed}。",);
        Ok(failed != 0)
    } else {
        if !input.is_file() {
            return Err(format!("输入文件不存在或不是普通文件：{}", input.display()));
        }
        if output.exists() {
            println!("跳过：目标文件已存在：{}", output.display());
            return Ok(false);
        }
        let result = smooth_file_grouped(&input, &output, &groups).map_err(|e| e.to_string())?;
        println!(
            "处理完成：{} → {}（{} 个所选轨道关键帧）",
            input.display(),
            output.display(),
            result.output_keys
        );
        print_warnings(&output, &result.warnings);
        Ok(false)
    }
}

fn value(args: &mut impl Iterator<Item = String>, flag: &str) -> Result<String, String> {
    args.next().ok_or_else(|| format!("{flag} 缺少参数"))
}

fn print_warnings(path: &std::path::Path, warnings: &[String]) {
    for warning in warnings {
        eprintln!("警告 [{}]：{warning}", path.display());
    }
}
