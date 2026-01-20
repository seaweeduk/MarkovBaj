package backend

import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

suspend fun lidlboards(
    call: ApplicationCall,
    latestTableValuesUpdateInstant: kotlin.time.Instant?,
    tableValues: Map<String, Pair<List<String>, List<List<String>>>>
) {
    call.respondHtml {
        head {
            title("MarkovBaj Lidlboards")

            meta(name = "viewport", content = "width=device-width, initial-scale=1")

            style {
                unsafe {
                    +"""
                        body {
                            font-family: Arial;
                            margin: 0;
                            position: relative;
                        }
                        div.header {
                            position: sticky;
                            top: 0;
                            background-color: cornflowerblue;
                            color: white;
                            display: grid;
                            grid-template-columns: 1fr 1fr;
                            grid-template-rows: 1fr;
                            align-items: center;
                            padding-left: 32px;
                            padding-right: 32px;
                            padding-top: 16px;
                            padding-bottom: 16px;
                            word-break: break-word;
                        }
                        div.content {
                            padding: 16px;
                            display: flex;
                            gap: 16px;
                            align-items: flex-start;
                            overflow-x: scroll;
                        }
                        table {
                            min-width: 500px;
                            border: 1px solid black;
                            border-collapse: collapse;
                        }
                        button.expand-toggle {
                            margin: 8px;
                            padding: 8px;
                            flex-shrink: 0;
                        }
                        table.collapsed tr:not(:first-child) {
                            display: none;
                        }
                        tr {
                            border: 1px solid black;
                        }
                        th, td {
                            text-align: left;
                            padding: 8px;
                        }
                        @media (max-width: 480px) {
                            table {
                                width: 100vw;
                                min-width: auto;
                                word-break: break-word;
                                font-size: 10pt;
                            }
                            th, td {
                                padding: 6px;
                            }
                            div.header {
                                grid-template-columns: 1fr;
                                grid-template-rows: auto auto;
                                justify-items: center;
                                padding-top: 8px;
                                padding-bottom: 8px;
                            }
                            div.header div {
                                margin-top: 8px;
                                margin-bottom: 8px;
                                text-align: center !important;
                            }
                            div.content {
                                padding: 8px;
                                flex-wrap: wrap;
                                justify-items: center;
                                overflow-x: visible;
                            }
                        }
                    """.trimIndent()
                }
            }

            script {
                unsafe {
                    +"""
                        function updateExpandToggleButtons() {
                            for (const button of document.querySelectorAll('button.expand-toggle')) {
                                if (button.closest('table').classList.contains('collapsed')) {
                                    button.textContent = 'Expand';
                                } else {
                                    button.textContent = 'Collapse';
                                }
                            }
                        }
                        
                        addEventListener('DOMContentLoaded', _ => {
                            window.onresize();
                            updateExpandToggleButtons();
                        });
                        
                        let lastWindowWidth = window.innerWidth - 1;
                        
                        window.onresize = _ => {
                            if (window.innerWidth < 480 && window.innerWidth !== lastWindowWidth) {
                                for (const table of document.querySelectorAll('table')) {
                                    table.classList.add('collapsed');
                                }
                                
                                updateExpandToggleButtons();
                                
                                lastWindowWidth = window.innerWidth;
                            }
                        };
                    """.trimIndent()
                }
            }
        }

        body {
            div(classes = "header") {
                div {
                    style = "font-size: 32px;"
                    +"MarkovBaj Lidlboards"
                }

                div {
                    style = "text-align: right;"
                    +"Last updated at: $latestTableValuesUpdateInstant"
                }
            }

            div(classes = "content") {
                tableValues.forEach { (tableDisplayName, values) ->
                    val (headers, rows) = values

                    table {
                        tr {
                            th {
                                colSpan = headers.size.toString()

                                span {
                                    style = "display: flex; justify-content: space-between; align-items: center; padding-left: 16px;"

                                    +tableDisplayName

                                    button(classes = "expand-toggle") {
                                        onClick = "this.closest('table').classList.toggle('collapsed'); updateExpandToggleButtons();"
                                        +""
                                    }
                                }
                            }
                        }

                        tr {
                            headers.forEach { th { +it } }
                        }

                        rows.forEach { row ->
                            tr {
                                row.forEach {
                                    td {
                                        if (it.startsWith("https://")) {
                                            a(href = it) {
                                                +"Link"
                                            }
                                        } else {
                                            +it
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
