document.addEventListener("DOMContentLoaded", function () {

    const ctx = document.getElementById("accountTypeChart");
    const chartData = window.accountTypeChartData || {};
    const labels = chartData.labels || [];
    const values = chartData.values || [];

    new Chart(ctx, {
        type: "doughnut",
        data: {
            labels: labels,
            datasets: [{
                data: values,
                backgroundColor: [
                    "#315B8C",
                    "#F2765E",
                    "#2F6B4F",
                    "#8A6A18",
                    "#89AFE4",
                    "#FFB6A2",
                    "#6E5B55",
                    "#D6C6BB"
                ],
                borderWidth: 2,
                borderColor: "#FAF3E9"
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            cutout: "65%",
            plugins: {
                legend: {
                    position: "bottom",
                    labels: {
                        padding: 20,
                        usePointStyle: true,
                        pointStyle: "circle"
                    }
                },
                tooltip: {
                    callbacks: {
                        label: function(context) {
                            return context.label + ": " + context.raw + " tài khoản";
                        }
                    }
                }
            }
        }
    });

});
