<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, user-scalable=yes, initial-scale=1.0">
    <title>${file.name}文本预览</title>
    <#include "*/commonHeader.ftl">
    <script src="js/jquery-3.6.1.min.js" type="text/javascript"></script>
    <link rel="stylesheet" href="bootstrap/css/bootstrap.min.css"/>
    <link rel="stylesheet" href="css/index.css"/>
    <script src="bootstrap/js/bootstrap.min.js" type="text/javascript"></script>
    <script src="js/marked.min.js" type="text/javascript"></script>
    <script src="js/base64.min.js" type="text/javascript"></script>
    <script src="js/codemirror.js" type="text/javascript"></script>
    <link rel="stylesheet" href="js/codemirror.css"/>
    <style>
    
    </style>
</head>
<body>
<input hidden id="textData" value="${textData}"/>

<!-- 目录区域 - 左侧 -->
<div id="directory">
    <div>文档目录</div>
    <div id="content">
        <ul></ul>
        <div class="empty-toc" style="display:none;">暂无目录</div>
    </div>
</div>

<!-- 主内容区域 -->
<div class="container">
    <div class="panel panel-default">
        <div class="panel-heading">
            <h6 class="panel-title">
                <a data-toggle="collapse" data-parent="#accordion" href="#collapseOne">
                    ${file.name}
                </a>
            </h6>
        </div>
        
        <!-- 视图切换按钮 -->
        <div class="view-toggle">
            <button id="preview_btn" class="view-btn active">预览模式</button>
            <button id="source_btn" class="view-btn">源代码</button>
        </div>
        
        <div class="panel-body">
            <div id="markdown"></div>
        </div>
    </div>
</div>

<textarea id="textarea" style="display:none;"></textarea>

<script>
    // 初始化编辑器
    var editor = CodeMirror.fromTextArea(document.getElementById('textarea'), { 
        mode: "text/html",
        lineNumbers: true,
        tabMode: "indent",
        lineWrapping: false,
        theme: "default",
        viewportMargin: Infinity
    });
    
    // 初始化目录
    function initTOC() {
        let html = "";
        let index = 0;
        
        // 从预览内容中提取标题
        $("#markdown h1, #markdown h2, #markdown h3, #markdown h4, #markdown h5").each(function() {
            let id = "heading-" + index++;
            $(this).attr('id', id);
            let level = this.tagName.toLowerCase().replace('h', '');
            let text = $(this).text().substring(0, 50);
            html += '<li class="li-h' + level + '"><a href="#' + id + '">' + text + '</a></li>';
        });
        
        $("#directory ul").html(html);
        
        // 显示/隐藏空目录提示
        if (html === "") {
            $("#directory .empty-toc").show();
            $("#directory ul").hide();
        } else {
            $("#directory .empty-toc").hide();
            $("#directory ul").show();
        }
        
        // 更新目录高度
        updateTOCHeight();
    }
    
    // 更新目录高度
    function updateTOCHeight() {
        const windowHeight = window.innerHeight;
        const tocTop = document.getElementById('directory').getBoundingClientRect().top;
        const availableHeight = windowHeight - tocTop - 20;
        document.getElementById('directory').style.maxHeight = availableHeight + 'px';
    }
    
    // 安全HTML转义函数
    function htmlEscape(str) {
        if (!str) return "";
        return str
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;")
            .replace(/javascript/gi, "javascript ");
    }
    
    // 加载markdown内容
    function loadMarkdown() {
        try {
            var textData = Base64.decode($("#textData").val());
            textData = htmlEscape(textData);
            
            window.textPreData = "<pre style='background-color: #f8f9fa;border:1px solid #e9ecef;border-radius:8px;padding:18px;overflow-x:auto;'>" + textData + "</pre>";
            window.textMarkdownData = marked.parse(textData);
            
            $("#markdown").html(window.textMarkdownData);
            editor.setValue(textData);
            
            // 初始化目录
            initTOC();
            
        } catch (e) {
            console.error("加载内容失败:", e);
            $("#markdown").html("<div class='alert alert-danger' style='padding:15px;border-radius:8px;'>加载内容失败: " + e.message + "</div>");
        }
    }
    
    // 切换视图
    function switchView(mode) {
        if (mode === 'preview') {
            $("#preview_btn").addClass('active');
            $("#source_btn").removeClass('active');
            $("#markdown").html(window.textMarkdownData);
            initTOC();
        } else {
            $("#source_btn").addClass('active');
            $("#preview_btn").removeClass('active');
            $("#markdown").html(window.textPreData);
            $("#directory .empty-toc").show();
            $("#directory ul").hide();
        }
    }
    
    // 页面加载完成
    $(document).ready(function() {
        // 初始化水印
        if (typeof initWaterMark === 'function') {
            initWaterMark();
        }
        
        // 加载markdown内容
        loadMarkdown();
        
        // 监听编辑器变化
        editor.on('change', function(cm) {
            // 更新预览
            var content = cm.getValue();
            window.textPreData = "<pre style='background-color: #f8f9fa;border:1px solid #e9ecef;border-radius:8px;padding:18px;overflow-x:auto;'>" + content + "</pre>";
            window.textMarkdownData = marked.parse(content);
            
            // 如果当前是预览模式，更新预览内容
            if ($("#preview_btn").hasClass('active')) {
                $("#markdown").html(window.textMarkdownData);
                initTOC();
            }
        });
        
        // 绑定视图切换事件
        $("#preview_btn").click(function() {
            switchView('preview');
        });
        
        $("#source_btn").click(function() {
            switchView('source');
        });
        
        // 为目录链接添加平滑滚动
        $(document).on('click', '#directory a', function(e) {
            e.preventDefault();
            var target = $(this.getAttribute('href'));
            if (target.length) {
                $('html, body').animate({
                    scrollTop: target.offset().top - 100
                }, 500);
            }
        });
        
        // 窗口调整大小时更新目录高度
        $(window).resize(function() {
            updateTOCHeight();
        });
        
        // 初始化目录高度
        setTimeout(updateTOCHeight, 100);
        
        // 添加键盘快捷键
        $(document).keydown(function(e) {
            // Ctrl+1 切换到预览模式
            if (e.ctrlKey && e.key === '1') {
                e.preventDefault();
                switchView('preview');
            }
            // Ctrl+2 切换到源代码模式
            else if (e.ctrlKey && e.key === '2') {
                e.preventDefault();
                switchView('source');
            }
        });
        
        // 添加目录项悬停效果
        $(document).on('mouseenter', '#directory li a', function() {
            $(this).parent().addClass('hover');
        }).on('mouseleave', '#directory li a', function() {
            $(this).parent().removeClass('hover');
        });
    });
</script>
</body>
</html>