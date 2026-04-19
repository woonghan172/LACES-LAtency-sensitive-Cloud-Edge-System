#!/usr/bin/env python3
"""
Weight Tuning 最佳配置查找工具 (Pure Python)
分析 metrics_per_iteration.csv，找出最佳的 weight 組合
"""

import csv
from pathlib import Path
import argparse
from collections import defaultdict

def read_csv_data(csv_file):
    """讀取 CSV 檔案"""
    data = []
    with open(csv_file, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            data.append({
                'app': row['app'],
                'w1': float(row['w1']),
                'w2': float(row['w2']),
                'w3': float(row['w3']),
                'device_count': int(row['device_count']),
                'service_time': float(row['service_time']),
                'failed_task': int(row['failed_task']),
                'iteration': int(row['iteration']),
            })
    return data

def calculate_median(values):
    """計算中位數"""
    sorted_values = sorted(values)
    n = len(sorted_values)
    if n % 2 == 0:
        return (sorted_values[n//2 - 1] + sorted_values[n//2]) / 2
    else:
        return sorted_values[n//2]

def calculate_stats(data):
    """計算統計數據 - 使用中位數"""
    stats = defaultdict(lambda: {'service_times': [], 'failed_tasks': []})
    
    for record in data:
        key = (record['w1'], record['w2'], record['w3'])
        stats[key]['service_times'].append(record['service_time'])
        stats[key]['failed_tasks'].append(record['failed_task'])
    
    # 計算中位數而不是平均值
    result = []
    for (w1, w2, w3), values in stats.items():
        service_times = values['service_times']
        failed_tasks = values['failed_tasks']
        
        median_service = calculate_median(service_times)
        median_failed = calculate_median(failed_tasks)
        
        result.append({
            'w1': w1,
            'w2': w2,
            'w3': w3,
            'avg_service_time': median_service,  # 實際上是中位數
            'avg_failed_tasks': median_failed,   # 實際上是中位數
            'runs': len(service_times),
            'service_times': service_times,
            'failed_tasks': failed_tasks,
        })
    
    return result

def normalize(values, key):
    """正規化數據到 0-1 範圍"""
    values_list = [v[key] for v in values]
    min_val = min(values_list)
    max_val = max(values_list)
    
    if max_val == min_val:
        return {(v['w1'], v['w2'], v['w3']): 0 for v in values}
    
    normalized = {}
    for v in values:
        norm_val = (v[key] - min_val) / (max_val - min_val)
        normalized[(v['w1'], v['w2'], v['w3'])] = norm_val
    
    return normalized

def find_best_weights(csv_file, app_filter=None):
    """找出最佳的 weight 配置"""
    print(f"✓ 讀取數據: {csv_file}\n")
    
    data = read_csv_data(csv_file)
    print(f"✓ 已讀取 {len(data)} 筆記錄")
    
    # 按應用過濾
    if app_filter:
        data = [d for d in data if d['app'] == app_filter]
        print(f"✓ 按應用 '{app_filter}' 過濾: {len(data)} 筆記錄")
    
    # 顯示有多少個不同的應用
    apps = set(d['app'] for d in data)
    print(f"✓ 數據中包含應用: {', '.join(apps)}\n")
    
    print("=" * 80)
    print("分析 Weight 組合")
    print("=" * 80)
    
    # 計算統計
    stats = calculate_stats(data)
    
    # 過濾异常值 (service_time > 1.5)
    normal_stats = [s for s in stats if s['avg_service_time'] < 1.5]
    print(f"✓ 過濾后剩餘 {len(normal_stats)} 個有效配置\n")
    
    # 正規化評分
    service_norm = normalize(normal_stats, 'avg_service_time')
    failed_norm = normalize(normal_stats, 'avg_failed_tasks')
    
    # 計算綜合評分 (服務時間 70%，失敗任務 30%)
    for s in normal_stats:
        key = (s['w1'], s['w2'], s['w3'])
        s['combined_score'] = 0.7 * service_norm[key] + 0.3 * failed_norm[key]
    
    # 排序
    normal_stats.sort(key=lambda x: x['combined_score'])
    
    # 顯示前 10 個最佳配置
    print("\n【前 10 最佳配置（基於中位數）】")
    print("-" * 80)
    print(f"{'排名':<5} {'W1':<10} {'W2':<10} {'W3':<10} {'中位服務時間':<15} {'中位失敗任務':<12} {'評分':<8}")
    print("-" * 80)
    
    for idx, s in enumerate(normal_stats[:10], 1):
        print(f"{idx:<5} {s['w1']:<10.4f} {s['w2']:<10.4f} {s['w3']:<10.4f} {s['avg_service_time']:<15.6f} {s['avg_failed_tasks']:<12.2f} {s['combined_score']:<8.4f}")
    
    print("\n" + "=" * 80)
    print("【最佳配置】")
    print("=" * 80)
    best = normal_stats[0]
    print(f"✓ W1: {best['w1']:.4f}  W2: {best['w2']:.4f}  W3: {best['w3']:.4f}")
    print(f"  - 中位服務時間: {best['avg_service_time']:.6f}")
    print(f"  - 中位失敗任務: {best['avg_failed_tasks']:.2f}")
    print(f"  - 綜合評分: {best['combined_score']:.4f}")
    print(f"  - 運行次數: {best['runs']}")
    print(f"\n  - 詳細 (Iteration 數據):")
    print(f"    服務時間: {[f'{t:.6f}' for t in best['service_times']]}")
    print(f"    失敗任務: {best['failed_tasks']}")
    
    # 按設備數量分析
    print("\n" + "=" * 80)
    print("【按設備數量分析最佳配置（基於中位數）】")
    print("=" * 80)
    
    device_counts = sorted(set(d['device_count'] for d in data))
    
    for device_count in device_counts:
        device_data = [d for d in data if d['device_count'] == device_count]
        device_stats = calculate_stats(device_data)
        device_stats = [s for s in device_stats if s['avg_service_time'] < 1.5]
        
        if device_stats:
            device_service_norm = normalize(device_stats, 'avg_service_time')
            device_failed_norm = normalize(device_stats, 'avg_failed_tasks')
            
            for s in device_stats:
                key = (s['w1'], s['w2'], s['w3'])
                s['combined_score'] = 0.7 * device_service_norm[key] + 0.3 * device_failed_norm[key]
            
            device_stats.sort(key=lambda x: x['combined_score'])
            best_device = device_stats[0]
            
            print(f"\n設備數: {device_count}")
            print(f"  最佳: W1={best_device['w1']:.4f}, W2={best_device['w2']:.4f}, W3={best_device['w3']:.4f}")
            print(f"    → 中位服務時間: {best_device['avg_service_time']:.6f}, 中位失敗任務: {best_device['avg_failed_tasks']:.2f}")
            print(f"    → 詳細 (Iteration): 服務時間 {[f'{t:.6f}' for t in best_device['service_times']]} | 失敗任務 {best_device['failed_tasks']}")

def compare_weights(csv_file, w1, w2, w3):
    """比較特定 weight 配置"""
    data = read_csv_data(csv_file)
    subset = [d for d in data if d['w1'] == w1 and d['w2'] == w2 and d['w3'] == w3]
    
    if not subset:
        print(f"找不到 W1={w1}, W2={w2}, W3={w3} 的數據")
        return
    
    print(f"W1={w1}, W2={w2}, W3={w3} 的性能統計:\n")
    
    service_times = [d['service_time'] for d in subset]
    failed_tasks = [d['failed_task'] for d in subset]
    
    avg_service = sum(service_times) / len(service_times)
    median_service = calculate_median(service_times)
    avg_failed = sum(failed_tasks) / len(failed_tasks)
    median_failed = calculate_median(failed_tasks)
    
    print(f"  服務時間:")
    print(f"    平均: {avg_service:.6f}")
    print(f"    中位數: {median_service:.6f}")
    print(f"    最小: {min(service_times):.6f}")
    print(f"    最大: {max(service_times):.6f}")
    print(f"\n  失敗任務:")
    print(f"    平均: {avg_failed:.2f}")
    print(f"    中位數: {median_failed:.2f}")
    print(f"    最小: {min(failed_tasks)}")
    print(f"    最大: {max(failed_tasks)}")
    
    print(f"\n詳細數據:")
    print(f"{'設備數':<10} {'迭代':<8} {'服務時間':<12} {'失敗任務':<10}")
    print("-" * 40)
    for d in sorted(subset, key=lambda x: (x['device_count'], x['iteration'])):
        print(f"{d['device_count']:<10} {d['iteration']:<8} {d['service_time']:<12.6f} {d['failed_task']:<10}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Weight Tuning 分析工具")
    parser.add_argument("csv", nargs="?", help="CSV 檔案路徑")
    parser.add_argument("--app", type=str, help="指定應用程式 (如: health, info)")
    parser.add_argument("--compare", action="store_true", help="比較特定 weight 配置")
    parser.add_argument("--w1", type=float, help="W1 值")
    parser.add_argument("--w2", type=float, help="W2 值")
    parser.add_argument("--w3", type=float, help="W3 值")
    
    args = parser.parse_args()
    
    # 找 CSV 檔案
    if args.csv:
        csv_file = args.csv
    else:
        # 自動找最新的 CSV
        base_path = Path("/workspace/EdgeCloudSim/scripts/laces/output/weight_tuning")
        if base_path.exists():
            files = list(base_path.rglob("metrics_per_iteration.csv"))
            if files:
                csv_file = str(max(files, key=lambda p: p.stat().st_mtime))
                print(f"✓ 使用最新的 CSV: {csv_file}\n")
            else:
                print("❌ 找不到 metrics_per_iteration.csv 檔案")
                exit(1)
        else:
            print("❌ 路徑不存在")
            exit(1)
    
    if args.compare and args.w1 is not None and args.w2 is not None and args.w3 is not None:
        compare_weights(csv_file, args.w1, args.w2, args.w3)
    else:
        find_best_weights(csv_file, app_filter=args.app)
