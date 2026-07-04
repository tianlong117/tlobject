import os

# 获取当前目录
current_dir = os.getcwd()
print(f"当前目录: {current_dir}")
print("-" * 40)

# 列出所有文件和文件夹
files = os.listdir(".")
print(f"共 {len(files)} 个项目:\n")

for f in files:
    full_path = os.path.join(current_dir, f)
    if os.path.isdir(full_path):
        print(f"  📁 {f}/")
    else:
        size = os.path.getsize(full_path)
        print(f"  📄 {f}  ({size} bytes)")

print("\n搞定！猪爷出品，必属精品！")
